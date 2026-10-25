#!/system/bin/sh
# Loads the guest ROM's build properties into the shared property area.
#
# The container has no init of its own, so nothing reads the guest's build.prop
# files: the guest system_server only ever sees the host's (Samsung's) props.
# ColorOS gates a lot of its framework on ro.oplus.* / persist.oplus.* values
# that only exist in its own prop files. Only props the host does not define
# are added, so One UI keeps its own values, and every added name is recorded
# so `guest_props.sh remove` can delete them again on the way back.
#
# Usage: guest_props.sh add <rootfs> | remove | snapshot-persist |
#        restore-persist | recover
STAGE="${STAGE:-/data/local/tmp/oneos}"
ADDED="$STAGE/guest_props.added"
# Host values that `add` overrides for the session (key=host value);
# `remove` puts them back before One UI's system_server reads them.
OVR="$STAGE/guest_props.overridden"
# The root manager's own resetprop (KernelSU, APatch), never a search of /data.
# Missing is fatal: exiting 0 here (OneLineage) skipped the persist snapshot and
# its restore without a word, and One UI then booted with the guest's settings.
RP=""
for c in /data/adb/ksu/bin/resetprop /data/adb/ap/bin/resetprop; do
    [ -x "$c" ] && { RP=$c; break; }
done
[ -n "$RP" ] || [ "${ONEOS_APP:-0}" = 1 ] || RP=$(command -v resetprop 2>/dev/null)
if [ -z "$RP" ] || [ ! -x "$RP" ]; then
    echo "guest_props: ERROR: resetprop not found"
    exit 1
fi
SNAP_FILE="$STAGE/persist_props.snapshot"
MARK="$STAGE/persist_session.active"
BOOT_ID=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
sha() { sha256sum "$1" 2>/dev/null | cut -d' ' -f1; }
mark_field() { sed -n "s/^$1=//p" "$MARK" 2>/dev/null | head -n 1; }
# The persist snapshot is only ever used when this stage's own session took it:
# persist_session.active (format 2, written by snapshot-persist) holds its
# sha256 and the sha256 of the app's stage marker .oneos_stage ("none" without
# one). A snapshot from an older stage (OneLineage's, or one set up before the
# app adopted the stage) could lack props One UI has now, and restore-persist
# deletes every persist prop missing from the snapshot.
snapshot_trusted() {
    [ -f "$MARK" ] && [ -f "$SNAP_FILE" ] || return 1
    [ "$(mark_field format)" = 2 ] || return 1
    [ -n "$(mark_field snapshot)" ] && [ "$(mark_field snapshot)" = "$(sha "$SNAP_FILE")" ] || return 1
    sm=none; [ -f "$STAGE/.oneos_stage" ] && sm=$(sha "$STAGE/.oneos_stage")
    [ "$(mark_field stage_marker)" = "$sm" ]
}
# Moved aside, never deleted: $STAGE/attic/<epoch>/.
set_aside() {
    a="$STAGE/attic/$(date +%s)"
    mkdir -p "$a" || return 1
    for f in "$@"; do [ -e "$f" ] && mv -f "$f" "$a/"; done
    echo "guest_props: moved to $a:$(for f in "$@"; do printf ' %s' "${f##*/}"; done)"
}
# Props set with resetprop -n live in memory only: after a reboot the lists of
# a session that never returned name nothing, and replaying an old
# guest_props.overridden would set whatever an older stage wrote there (the
# remove of a hard reset). `add` records the boot in guest_props.boot.
drop_stale_memory_lists() {
    if [ -f "$STAGE/guest_props.boot" ]; then
        [ "$(cat "$STAGE/guest_props.boot" 2>/dev/null)" = "$BOOT_ID" ] && return 0
    else
        bt=$(awk '/^btime/ {print $2}' /proc/stat 2>/dev/null)
        [ -n "$bt" ] || return 0
        for f in "$ADDED" "$OVR"; do
            [ -f "$f" ] && [ "$(stat -c %Y "$f" 2>/dev/null)" -ge "$bt" ] 2>/dev/null && return 0
        done
    fi
    for f in "$ADDED" "$OVR"; do
        [ -f "$f" ] && rm -f "$f" && echo "guest_props: ${f##*/} is from before this boot, dropped (its props died with the reboot)"
    done
    rm -f "$STAGE/guest_props.boot"
    return 0
}

case "$1" in
add)
    ROOTFS="$2"
    HOST="$STAGE/host_props.txt"
    NEW="$STAGE/guest_props.new"
    # A session that never returned left its overrides in place: put One UI's
    # values back first, so they are what gets saved again. Only lists from
    # this boot (see drop_stale_memory_lists).
    drop_stale_memory_lists
    if [ -f "$OVR" ]; then
        while IFS= read -r line; do "$RP" -n "${line%%=*}" "${line#*=}" 2>/dev/null; done < "$OVR"
        rm -f "$OVR"
    fi
    echo "$BOOT_ID" > "$STAGE/guest_props.boot"
    # One getprop for the whole host set instead of one per key.
    getprop | sed -n 's/^\[\([^]]*\)\].*/\1/p' > "$HOST"
    # Lowest priority first; a later file wins for a key defined twice.
    ls "$ROOTFS"/system/build.prop \
       "$ROOTFS"/system/system_ext/etc/build.prop \
       "$ROOTFS"/system/product/etc/build.prop \
       "$ROOTFS"/my_*/build.prop \
       "$ROOTFS"/my_*/etc/build.prop 2>/dev/null |
    xargs cat |
    awk -v hostfile="$HOST" '
        BEGIN { while ((getline k < hostfile) > 0) host[k] = 1 }
        /^[ \t]*(#|import|$)/ { next }
        {
            i = index($0, "="); if (i < 2) next
            key = substr($0, 1, i - 1); val = substr($0, i + 1)
            sub(/\?$/, "", key)
            if (key ~ /[^A-Za-z0-9._-]/ || (key in host)) next
            # Never configure the HOST daemons through the shared property
            # area. ColorOS sets apexd.config.compressed_apex=false; the
            # host apexd, started on demand by the guest, then cleaned up
            # /data/apex/decompressed under the APEXes One UI had mounted
            # (dm-verity I/O errors, SIGBUS in the One UI package parser,
            # boot loop). No apostrophes in here: this is a quoted awk script.
            if (key ~ /^(persist\.)?(apexd|ro\.apex|ro\.boot|ro\.bootimage|ro\.crypto|ro\.hardware|ro\.vndk|ro\.zygote|init|vold|ro\.vold|sys\.usb|persist\.sys\.usb|ro\.adb|ro\.debuggable|ro\.secure|selinux|ro\.build\.selinux|ro\.kernel|ro\.dalvik\.vm\.native\.bridge)\./) next
            if (key ~ /^(apexd|ro\.apex|init|vold|selinux)$/) next
            # Key attestation reports these as the device identity (KeyMint
            # checks them against the phone): always the phone values, never
            # the guest image ones (Pixel ships a yogi set).
            if (key ~ /_for_attestation$/) next
            if (!(key in seen)) order[n++] = key
            seen[key] = 1; value[key] = val
        }
        END { for (j = 0; j < n; j++) print order[j] "=" value[order[j]] }
    ' > "$NEW"
    # Window-level blur (run 31: glass UI flat in places). The Find N6 turns
    # it on in its vendor build.prop, which the GSI does not carry. The guest
    # SurfaceFlinger logged "Disabling blur effects, they are not supported."
    # (run 30 all.log), its RenderEngine chose blur algorithm None and
    # WindowManager had mBlurEnabled=false (run 31 diag_40). ColorOS's capture
    # blur (BlurService) still drew the shade and Control Center, but whatever
    # asks SurfaceFlinger for a background blur or checks
    # isCrossWindowBlurEnabled fell back to a flat dim: dialogs, the black
    # power menu, QS detail panels, the app drawer and Recents backdrop. The
    # guest framework reads it with getBoolean(..., true) and WindowManager
    # still said false, so One UI most likely defines it as 0: override it for
    # the session. ONEOS_GUEST_BLUR=0 leaves it alone.
    if [ "${ONEOS_GUEST_BLUR:-1}" = 1 ]; then
        BLUR=ro.surface_flinger.supports_background_blur
        if grep -qxF "$BLUR" "$HOST"; then
            hv=$(getprop "$BLUR")
            if [ "$hv" != 1 ] && "$RP" -n "$BLUR" 1 2>/dev/null; then
                echo "$BLUR=$hv" >> "$OVR"
                echo "guest_props: $BLUR=$hv overridden to 1 for the session (window blur)"
            fi
        elif ! grep -q "^$BLUR=" "$NEW"; then
            echo "$BLUR=1" >> "$NEW"
        fi
    fi
    # Bluetooth audio offload (run 44: the earbuds connected, audio stayed on the
    # speaker). A2DP keeps One UI's offload: Samsung's QTI HAL turns its offload
    # path on from the vendor parameter l_a2dp_format, which guest_start.sh now
    # sends (the HAL analysis: no property decides it; with offload forced off
    # the guest policy still connected A2DP on the primary module and the
    # bluetooth module's stream failed). LE Audio stays in software: the same
    # HAL bit drives the LE paths, and LE ports sit on the primary module too.
    # Memory only, One UI's value goes back with remove and restore-persist.
    # ColorOS guests keep the host values. ONEOS_GUEST_BT_OFFLOAD=1 leaves LE alone.
    # One guest-only prop out of the list (key, dots taken literally); 1 if the
    # list did not have it.
    drop_new() {
        dk=$(printf '%s' "$1" | sed 's/[.]/\\./g')
        grep -q "^$dk=" "$NEW" || return 1
        sed -i "/^$dk=/d" "$NEW"
    }
    GUEST_OPLUS=0
    [ -f "$ROOTFS/system/framework/oplus-services.jar" ] && GUEST_OPLUS=1
    if [ "${ONEOS_GUEST_BT_OFFLOAD:-0}" = 0 ] && [ "$GUEST_OPLUS" = 0 ]; then
        for k in persist.bluetooth.leaudio_offload.disabled; do
            if grep -qxF "$k" "$HOST"; then
                hv=$(getprop "$k")
                if [ "$hv" != true ] && "$RP" -n "$k" true 2>/dev/null; then
                    echo "$k=$hv" >> "$OVR"
                    echo "guest_props: $k=$hv overridden to true for the session (LE Audio encoded by the guest)"
                fi
            else
                sed -i "/^$k=/d" "$NEW"
                echo "$k=true" >> "$NEW"
                echo "guest_props: $k=true for the session (LE Audio encoded by the guest)"
            fi
        done
        # The image's other LE Audio offload props, which One UI does not define
        # (run 44 review). ro.bluetooth.leaudio_offload.supported=true, with One
        # UI's leaudio_offload.disabled=false, put the Pixel stack's LE Audio
        # codec on the DSP: codec_manager.cc "configure_data_path for encode"
        # and LE_AUDIO_HARDWARE_OFFLOAD capabilities read at 17:29:36 and
        # 17:32:25 (run 44 all.log), the HAL offload path that refused A2DP.
        # Left out, LE Audio stays in software even if the override above did
        # not take. persist.bluetooth.leaudio_sw_offload and
        # persist.vendor.audio.leaudio_sw_offload are for Pixel's own audio HAL:
        # neither the image's Bluetooth module, framework, services nor
        # audioserver libraries read them, nor Samsung's libaudiocorehal.qti.so,
        # so they could only reach Samsung's audio HAL process, which is
        # restarted after this. ONEOS_GUEST_BT_OFFLOAD=1 keeps all three.
        out=""
        for k in ro.bluetooth.leaudio_offload.supported persist.bluetooth.leaudio_sw_offload persist.vendor.audio.leaudio_sw_offload; do
            drop_new "$k" && out="$out $k"
        done
        [ -z "$out" ] || echo "guest_props: left out for the session (undefined like One UI's: LE Audio encoded by the guest):$out"
    fi
    # HFP (run 44: calls routed to the earbuds stayed silent). The image's
    # product build.prop sets bluetooth.sco.managed_by_audio=true and One UI
    # does not define it, so it was added. With it, Pixel's Telecom,
    # AudioService and Bluetooth stack leave opening SCO to the audio HAL:
    # Telecom set the earbuds active and the HAL patched [Telephony Rx -> BT
    # SCO Headset], but nobody called connectAudio, the guest audioserver only
    # ever sent BT_SCO=off, the stack stayed in BTA_AG_SCO_LISTEN_ST and PAL
    # logged "BT device 7 not connected or not ready" (all.log 17:34:00 to
    # 17:34:07). Samsung's QTI HAL never opens SCO itself; One UI's stack does.
    # Undefined (false), AudioService opens SCO through the stack and sends
    # BT_SCO=on, which reaches the HAL as setScoConfig through the standard
    # IBluetooth (no vendor extension needed). ColorOS guests keep the image's
    # props. ONEOS_GUEST_SCO_MANAGED=1 keeps it.
    SCO=bluetooth.sco.managed_by_audio
    if [ "$GUEST_OPLUS" = 0 ]; then
        if [ "${ONEOS_GUEST_SCO_MANAGED:-0}" = 1 ]; then
            grep -q "^bluetooth\.sco\.managed_by_audio=" "$NEW" &&
                echo "guest_props: $(grep "^bluetooth\.sco\.managed_by_audio=" "$NEW" | tail -n 1) kept for the session (ONEOS_GUEST_SCO_MANAGED=1: SCO left to the audio HAL)"
        elif drop_new "$SCO"; then
            echo "guest_props: $SCO left out for the session (undefined like One UI's: the guest's AudioService opens SCO through the Bluetooth stack)"
        fi
    fi
    # LE Audio without Android's Bluetooth audio HAL (S25 Ultra, X9U CN, app
    # 0.4.7, session e98f44ff: Bluetooth on -> com.android.bluetooth aborted
    # 2.5 s later, 19 times in a row, "[broadcaster.cc:1532] Initialize: HAL
    # requirements not met. Init aborted.", after "HalVersionManager: No
    # supported HAL version"). One UI turns every LE Audio profile on
    # (bluetooth.profile.bap.*, csip, hap, vcp, ccp, mcp), but only the SM8850
    # phones register android.hardware.bluetooth.audio.
    # IBluetoothAudioProviderFactory/default; the SM8750 and older ones
    # (probe reports: S25+, Z Fold7, S24 Ultra, S23s) register only Samsung's
    # vendor.samsung.hardware.bluetooth.audio.ISehBluetoothAudioProviderFactory,
    # which the guest's stack does not know, and its HIDL fallback is off
    # (no hwservicemanager for it). The guest's LE Audio broadcaster then
    # aborts the whole Bluetooth process. On such a phone the LE Audio
    # profiles are off for the session (memory only, One UI's values go back
    # with remove), so classic Bluetooth starts. ONEOS_GUEST_LE_AUDIO=1 keeps
    # them.
    # Only `service check` (a servicemanager lookup): `service list` asks every
    # service for its interface, and One UI's surfaceflinger is frozen behind
    # the gap splash here, so it never answered and the switch hung before the
    # guest started (0.4.11, S24 Ultra session 0210fa8a: OneOS logo, black,
    # reboot). Only an explicit "not found" turns LE Audio off.
    BTA=android.hardware.bluetooth.audio.IBluetoothAudioProviderFactory/default
    if [ "${ONEOS_GUEST_LE_AUDIO:-0}" != 1 ] &&
        timeout 5 service check "$BTA" 2>/dev/null | grep -q ": not found"; then
        out=""
        for k in bluetooth.profile.bap.broadcast.source.enabled bluetooth.profile.bap.broadcast.assist.enabled \
                 bluetooth.profile.bap.unicast.client.enabled bluetooth.profile.csip.set_coordinator.enabled \
                 bluetooth.profile.hap.client.enabled bluetooth.profile.vcp.controller.enabled \
                 bluetooth.profile.ccp.server.enabled bluetooth.profile.mcp.server.enabled; do
            drop_new "$k" && out="$out $k(guest)"
            grep -qxF "$k" "$HOST" || continue
            hv=$(getprop "$k")
            if [ "$hv" != false ] && "$RP" -n "$k" false 2>/dev/null; then
                echo "$k=$hv" >> "$OVR"
                out="$out $k"
            fi
        done
        echo "guest_props: no $BTA on this phone: LE Audio off for the session:${out:- nothing was on}"
    fi
    # Power key stall (run 32: the power menu ~15 s late or missing after a
    # long press, Power + Volume Down screenshots late, folds handled late).
    # ColorOS's lib_inputflingerEx.so runs saupwkProcessKey on system_server's
    # InputReader thread for every power key press and looks up OPPO's
    # IOplusProject HAL with AServiceManager_getService, which polls for 5 s
    # when the service does not exist (it never does on this phone). Every key,
    # touch and lid event waits behind it: run 32 all.log:564703 "[theia_saupwk]
    # prepare send key event by hidl", "Waiting for service
    # 'vendor.oplus.hardware.stability.oplus_project.IOplusProject/default'",
    # 5 s later "didn't start" (25 times); 4492 EventHub "Slow read detected",
    # up to 13.2 s (all.log:579475); the lid opened at 13:11:49 was committed
    # at 13:12:03 (all.log:579291). The function first checks
    # property_get_bool(persist.sys.saupwk_test) and, when it is set, returns
    # before the HAL lookup ("test return, don't write
    # POWERKEY_InputReaderProcessKey"). Only OPPO's Theia power key statistics
    # are skipped. Memory only (resetprop -n), deleted again by remove.
    # ONEOS_GUEST_SAUPWK=0 leaves it out.
    if [ "${ONEOS_GUEST_SAUPWK:-1}" = 1 ] && ! grep -qxF persist.sys.saupwk_test "$HOST"; then
        sed -i '/^persist\.sys\.saupwk_test=/d' "$NEW"
        echo "persist.sys.saupwk_test=1" >> "$NEW"
        echo "guest_props: persist.sys.saupwk_test=1 for the session (no 5 s HAL wait per power key press)"
    fi
    # Inner screen status bar (Fold layout): the image brings the Find N6's
    # hole 2102,28:2176,102. OplusStatusBarUtils.isHoleType calls a hole
    # entirely right of the middle (2256/2 = 1128) HOLE_RIGHT, and
    # StatusBarPaddingProvider.updatePaddingLeftRight then ends the system icons
    # at the hole's left edge plus 36 px: x 2066 (run 36a all.log 07:14:27,
    # paddingResult (58, 190)), so they ran under this phone's camera at
    # 1665..1747. With the real hole they would end left of the camera and the
    # free band right of it would stay empty. A hole with left >= right is
    # NO_HOLE_TYPE: corner padding only on both sides, so the icons sit at the
    # right edge, in that band. The cover keeps the image's secondary hole.
    # ONEOS_GUEST_INNER_HOLE=image keeps the image's value.
    if [ "${ONEOS_GUEST_FORM:-fold}" = fold ] && [ "${ONEOS_GUEST_INNER_HOLE:-none}" = none ] &&
        grep -q '^ro\.oplus\.display\.screenhole\.positon=' "$NEW"; then
        sed -i 's/^ro\.oplus\.display\.screenhole\.positon=.*/ro.oplus.display.screenhole.positon=0,0:0,0/' "$NEW" &&
            echo "guest_props: ro.oplus.display.screenhole.positon=0,0:0,0 for the session (no inner hole: status icons at the right edge, right of the camera)"
    fi
    # Bar phones: the image's hole is its own phone's, compared raw with this
    # panel's width (isHoleType: left < width/2 < right is HOLE_CENTER_TYPE).
    # The S25 got the Find X9 Ultra's 679,40:762,123 on 1080 px: HOLE_RIGHT,
    # icons ended next to the camera. This phone's hole as One UI reported it
    # (oneos_switch.sh, host_screenhole); unread = no hole, icons at the
    # right edge. Top and bottom only pad the icons vertically
    # (StatusBarPaddingProvider.updatePaddingTopBottom).
    # ONEOS_GUEST_INNER_HOLE=image keeps the image's value here too.
    if [ "${ONEOS_GUEST_FORM:-fold}" != fold ] && [ "${ONEOS_GUEST_INNER_HOLE:-none}" = none ] &&
        grep -q '^ro\.oplus\.display\.screenhole\.positon=' "$NEW"; then
        h=$(cat "$STAGE/host_screenhole" 2>/dev/null) what="this phone's camera hole"
        echo "$h" | grep -qx '[0-9]\{1,5\},[0-9]\{1,5\}:[0-9]\{1,5\},[0-9]\{1,5\}' ||
            { h="0,0:0,0"; what="no hole, this phone's was not read"; }
        was=$(sed -n 's/^ro\.oplus\.display\.screenhole\.positon=//p' "$NEW" | tail -n 1)
        sed -i "s/^ro\.oplus\.display\.screenhole\.positon=.*/ro.oplus.display.screenhole.positon=$h/" "$NEW" &&
            echo "guest_props: ro.oplus.display.screenhole.positon=$h for the session ($what; the image's: $was)"
    fi
    # AppSearch isolated storage: Pixel sets ro.appsearch.feature.enable_isolated_storage=true,
    # and system_server's AppSearch then starts a protected VM through the
    # virtualization APEX, which is One UI's here (run 41: UnsatisfiedLinkError in
    # libvirtualmachine_jni, appsearch crashed twice; had it loaded, the guest would
    # have run a VM on One UI's virtualizationservice, like composd). Off for the
    # session; the property is only read when system_server starts.
    if grep -q '^ro\.appsearch\.feature\.enable_isolated_storage=true$' "$NEW"; then
        sed -i 's/^ro\.appsearch\.feature\.enable_isolated_storage=.*/ro.appsearch.feature.enable_isolated_storage=false/' "$NEW" &&
            echo "guest_props: ro.appsearch.feature.enable_isolated_storage=false for the session (no AppSearch VM on One UI's virtualization service)"
    fi
    # Night Light (run 42): turning it on faded the screen to black. Pixel's
    # framework-res takes config_nightDisplayAvailable from
    # config_setColorTransformAccelerated, which Pixel's own vendor overlay sets
    # and Samsung's vendor (the guest's) does not: the colour matrix stayed all
    # zeros ("The color transform's last row must be (0, 0, 0, 1)", "Could not
    # invert colorTransform!"). The image's static
    # product/overlay/treble-overlay-NightMode.apk sets it to true when
    # persist.sys.overlay.nightmode=true, which nothing sets. Added with the
    # others: memory only (resetprop -n), never in persistent storage; remove
    # deletes it, and restore-persist deletes every persist prop the switch's
    # snapshot (taken before this) lacks. Only with that overlay in the image
    # (Pixel), and not over a value One UI has. ONEOS_GUEST_NIGHT_LIGHT=0 leaves
    # it out.
    NM=persist.sys.overlay.nightmode
    if [ "${ONEOS_GUEST_NIGHT_LIGHT:-1}" = 1 ] && [ -f "$ROOTFS/system/product/overlay/treble-overlay-NightMode.apk" ]; then
        if grep -qxF "$NM" "$HOST"; then
            echo "guest_props: $NM is One UI's ($(getprop "$NM")), left alone"
        else
            sed -i '/^persist\.sys\.overlay\.nightmode=/d' "$NEW"
            echo "$NM=true" >> "$NEW"
            echo "guest_props: $NM=true for the session (Night Light: the image's treble NightMode overlay)"
        fi
    fi
    if "$RP" -n -f "$NEW" 2>/dev/null; then
        cut -d= -f1 "$NEW" > "$ADDED"
    else
        # Older resetprop without -f: fall back to one call per prop.
        : > "$ADDED"
        while IFS= read -r line; do
            "$RP" -n "${line%%=*}" "${line#*=}" 2>/dev/null && echo "${line%%=*}" >> "$ADDED"
        done < "$NEW"
    fi
    echo "guest_props: added $(wc -l < "$ADDED") props from the guest's build.prop files"
    rm -f "$HOST" "$NEW"
    # ColorOS's default sounds. One UI defines ro.config.ringtone,
    # notification_sound and alarm_alert, so the guest read Samsung's names (run
    # 30, Settings_RingtoneUtils: "getInternalRingtoneUri ACH_Galaxy_Bells.ogg
    # null") and RingtoneManager.ensureDefaultRingtones never set a default
    # ringtone or notification sound. For these keys only, the guest's file name
    # wins for the session (guest_prepare.sh stages the files).
    # ONEOS_GUEST_RINGTONES=0 keeps One UI's names.
    if [ "${ONEOS_GUEST_RINGTONES:-1}" = 1 ]; then
        for key in ro.config.ringtone ro.config.notification_sound ro.config.alarm_alert; do
            # The same files and order as above (Pixel sets these in product).
            g=$(cat "$ROOTFS"/system/build.prop "$ROOTFS"/system/system_ext/etc/build.prop \
                "$ROOTFS"/system/product/etc/build.prop "$ROOTFS"/my_*/build.prop 2>/dev/null | sed -n "s/^$key=//p" | tail -n 1)
            h=$(getprop "$key")
            [ -n "$g" ] && [ -n "$h" ] && [ "$g" != "$h" ] || continue
            echo "$key=$h" >> "$OVR" && "$RP" -n "$key" "$g" 2>/dev/null &&
                echo "guest_props: $key=$g for the session (One UI: $h)"
        done
    fi
    # The audio HAL adapter's vendor extension. One UI sets
    # ro.audio.ihaladaptervendorextension_enabled=true; the Pixel GSI sets false
    # (no such service on another vendor). With One UI's value the guest
    # audioserver waited forever in DeviceHalAidl initCheck for
    # IHalAdapterVendorExtension/default (run 38: 123 failed starts, no
    # media.audio_flinger). The guest's value wins for the session, the same way
    # as the sound names; ColorOS's images set true, like One UI.
    # With false the guest audioserver forwards no vendor keys to the HAL at
    # all: libaudiohal drops them without a log line and AudioFlinger still
    # returns OK (run 43: SIM 2 calls silent, g_call_sim_slot never arrived).
    # g_call_sim_slot then reaches the HAL only through guest_start.sh's call
    # keeper, which sends it to IModule/default directly while this is not true.
    for key in ro.audio.ihaladaptervendorextension_enabled; do
        g=$(cat "$ROOTFS"/system/build.prop "$ROOTFS"/system/system_ext/etc/build.prop \
            "$ROOTFS"/system/product/etc/build.prop "$ROOTFS"/my_*/build.prop 2>/dev/null | sed -n "s/^$key=//p" | tail -n 1)
        h=$(getprop "$key")
        [ -n "$g" ] && [ -n "$h" ] && [ "$g" != "$h" ] || continue
        echo "$key=$h" >> "$OVR" && "$RP" -n "$key" "$g" 2>/dev/null &&
            echo "guest_props: $key=$g for the session (One UI: $h)"
    done
    # No isolated compilation from the guest. SystemServer starts
    # IsolatedCompilationService when ro.config.isolated_compilation_enabled is
    # true (One UI: true); it schedules compiles in the HOST's composd (One UI's
    # VM, One UI's /data/misc/apexdata) for staged APEXes the shared apexd
    # reports. Off for every guest, restored on return.
    key=ro.config.isolated_compilation_enabled
    h=$(getprop "$key")
    if [ "$h" = true ]; then
        echo "$key=$h" >> "$OVR" && "$RP" -n "$key" false 2>/dev/null &&
            echo "guest_props: $key=false for the session (the guest never drives One UI's composd)"
    fi
    ;;
remove)
    # Host values add overrode for the session (blur, sound names, audio HAL
    # extension, isolated compilation).
    if [ -f "$OVR" ]; then
        while IFS= read -r line; do
            "$RP" -n "${line%%=*}" "${line#*=}" 2>/dev/null && echo "guest_props: restored host ${line%%=*}=${line#*=}"
        done < "$OVR"
        rm -f "$OVR"
    fi
    rm -f "$STAGE/guest_props.boot"
    [ -f "$ADDED" ] || exit 0
    removed=0
    while IFS= read -r key; do
        "$RP" --delete "$key" 2>/dev/null && removed=$((removed + 1))
    done < "$ADDED"
    rm -f "$ADDED"
    echo "guest_props: removed $removed guest props"
    ;;
snapshot-persist)
    # persist.* values the host has right now, before the guest can change
    # them. A running guest saves its own persist.* settings through the
    # property service, which writes them to /data/property, so One UI would
    # boot with them. restore-persist undoes that.
    # A session whose cleanup never ran (the phone rebooted mid-session) left its
    # marker: delete what that guest added before trusting the current values
    # as the host's. Changed values are left alone; most are One UI's own
    # counters (radio resets, log indexes) that moved on since.
    # OneOS: only with a snapshot this stage's own session took (format 2,
    # snapshot_trusted); any other marker and snapshot are moved aside unused.
    drop_stale_memory_lists
    if [ -f "$MARK" ]; then
        if snapshot_trusted; then
            echo "guest_props: previous session was not cleaned up, removing its added persist props"
            sh "$0" restore-persist --added-only || { echo "guest_props: cleanup of the previous session failed, not taking a snapshot"; exit 1; }
        else
            echo "guest_props: persist snapshot left by another stage or an older session, not used"
            set_aside "$MARK" "$SNAP_FILE" || { echo "guest_props: could not move it aside, not taking a snapshot"; exit 1; }
        fi
    fi
    getprop | sed -n 's/^\[\(persist\.[^]]*\)\]: \[\(.*\)\]$/\1=\2/p' > "$SNAP_FILE"
    [ -s "$SNAP_FILE" ] || { echo "guest_props: ERROR: no persist props read"; rm -f "$SNAP_FILE"; exit 1; }
    sm=none; [ -f "$STAGE/.oneos_stage" ] && sm=$(sha "$STAGE/.oneos_stage")
    { echo "format=2"
      echo "boot_id=$BOOT_ID"
      echo "epoch=$(date +%s)"
      echo "app=${ONEOS_APP:-0}"
      echo "profile=${ONEOS_PROFILE:-none}"
      echo "stage_marker=$sm"
      echo "snapshot=$(sha "$SNAP_FILE")"; } > "$MARK.tmp" && mv -f "$MARK.tmp" "$MARK" ||
        { echo "guest_props: ERROR: could not write $MARK"; exit 1; }
    echo "guest_props: saved $(wc -l < "$SNAP_FILE") host persist props"
    ;;
recover)
    # The app, after a hard reset (the phone was force-restarted during a
    # session, so return_to_oneui.sh never ran): One UI booted with the persist
    # props the guest saved, its locale among them (persist.sys.locale is shared
    # and oneos_switch.sh sets the guest's). Put One UI's values back now,
    # as the return would have, instead of waiting for the next switch (which
    # only deletes the added ones). Only a snapshot an app-launched session of
    # this very stage took (app=1, the same .oneos_stage), and only once that
    # session's boot is over. Exit 0 restored or nothing to do, 3 session may
    # still run (nothing touched), 4 snapshot not trusted (moved aside).
    drop_stale_memory_lists
    [ -f "$MARK" ] || { echo "guest_props: no unfinished session, nothing to recover"; exit 0; }
    if ! snapshot_trusted || [ "$(mark_field app)" != 1 ] || [ "$(mark_field stage_marker)" = none ]; then
        echo "guest_props: the unfinished session's snapshot was not taken by this stage's app session, not used"
        set_aside "$MARK" "$SNAP_FILE"
        exit 4
    fi
    if [ -z "$BOOT_ID" ] || [ "$(mark_field boot_id)" = "$BOOT_ID" ] ||
       awk -v s="$STAGE/" 'index($5, s) == 1' /proc/self/mountinfo | grep -q .; then
        echo "guest_props: the session of $(mark_field epoch) may still be running (same boot or the stage is mounted), not touched"
        exit 3
    fi
    echo "guest_props: session of $(mark_field epoch) (profile $(mark_field profile)) never returned, restoring One UI's persist props"
    sh "$0" restore-persist --recover || exit 1
    ;;
restore-persist)
    SNAP="$SNAP_FILE"
    [ -f "$SNAP" ] || { echo "guest_props: no persist snapshot, skipping"; exit 0; }
    snapshot_trusted || { echo "guest_props: persist snapshot not taken by this stage's session (no format 2 marker or hash mismatch), not used"; exit 0; }
    # Keep the guest's language for its next session (oneos_switch.sh
    # sets it again); One UI's own value is restored below.
    gl=$(getprop persist.sys.locale)
    if [ -n "$gl" ] && ! grep -qxF "persist.sys.locale=$gl" "$SNAP"; then
        echo "$gl" > "$STAGE/guest_locale"
        echo "guest_props: kept the guest's locale $gl for its next session"
    fi
    NOW="$STAGE/persist_props.now"
    getprop | sed -n 's/^\[\(persist\.[^]]*\)\]: \[\(.*\)\]$/\1=\2/p' > "$NOW"
    deleted=0; restored=0; failed=0
    # After a hard reset (recover) One UI's init has since recorded that boot
    # in persist.sys.boot.reason*: its own record, kept as it is.
    keep="^$"; [ "$2" = "--recover" ] && keep="^persist\.sys\.boot\.reason"
    # --recover may run long after the hard reset: One UI could have added or
    # changed its own persist props since (a new SIM's persist.radio.*, a
    # timezone or locale change the user made). The snapshot cannot tell those
    # from the guest's, so on --recover touch only props that are clearly the
    # guest's; the normal return (no flag, same boot) still restores everything.
    guest_prop() { # 0 when $1 is clearly a guest-owned persist prop
        case "$1" in
            persist.sys.locale) return 0 ;;
            persist.oplus.*|persist.sys.oplus.*) return 0 ;;
        esac
        return 1
    }
    # Added by the guest: delete from memory and from persistent storage.
    for key in $(cut -d= -f1 "$NOW"); do
        grep -q "^$key=" "$SNAP" && continue
        echo "$key" | grep -q "$keep" && continue
        [ "$2" = "--recover" ] && ! guest_prop "$key" && continue
        if "$RP" -p --delete "$key" 2>/dev/null; then deleted=$((deleted + 1)); else failed=$((failed + 1)); fi
    done
    # Changed by the guest: put the host's value back, in storage too.
    [ "$2" = "--added-only" ] && : > "$NOW.skip"
    while [ ! -f "$NOW.skip" ] && IFS= read -r line; do
        key=${line%%=*}
        grep -qxF "$line" "$NOW" && continue
        echo "$key" | grep -q "$keep" && continue
        [ "$2" = "--recover" ] && ! guest_prop "$key" && continue
        if "$RP" -p "$key" "${line#*=}" 2>/dev/null; then restored=$((restored + 1)); else failed=$((failed + 1)); fi
    done < "$SNAP"
    rm -f "$NOW" "$NOW.skip"
    # Fail closed: if any resetprop write failed, keep persist_session.active so
    # the app can show the failure and recover can retry, and exit non-zero.
    # Deleting the marker on a silent failure (the old behaviour) left One UI on
    # the guest's persist props with nothing able to see it any more.
    if [ "$failed" -gt 0 ]; then
        echo "guest_props: ERROR: $failed persist prop writes failed (deleted $deleted, restored $restored); keeping persist_session.active to retry"
        exit 1
    fi
    rm -f "$STAGE/persist_session.active"
    echo "guest_props: persist props: deleted $deleted added by the guest, restored $restored changed ones"
    ;;
*)
    echo "usage: $0 add <rootfs> | remove | snapshot-persist | restore-persist | recover" >&2
    exit 2
    ;;
esac
