# Pixel as the guest

> **Renamed after 0.3.6:** the stage is `/data/local/tmp/oneos` (was `/data/local/tmp/lineage_stage`), its set-aside copies `oneos.old-<epoch>` / `oneos.trash-<epoch>` (were `lineage_stage.old-*` / `.trash-*`), and the session scripts `oneos_switch.sh`, `guest_prepare.sh`, `guest_start.sh` (were `switch_to_lineage.sh`, `master_prepare.sh`, `master_inner.sh`). The app's root script and `scripts/deploy-phone.sh` rename an old stage and its copies when they are idle.

The first system other than ColorOS: the Pixel 11 Fold image (yogi,
`google/yogi/yogi:17/CD1A.261005.003.B1`), built with MysticGSI
(the image build (MysticGSI, not in this repository)`), `out/oneos-pixel-yogi.img`.
Target for the first run: the main test phone, a Z Fold (F8U), over adb.

## Framework patches (services.jar)

Pixel's services.jar is R8 output: public final methods, p registers, no
`.line` before some returns, and the IVold / IInstalld AIDL classes inside the
jar. The patchers match both shapes; on ColorOS's services.jar the whole chain
gives the same smali as before.

| Patch | Pixel |
|---|---|
| display_mode_specs | applies (Google's SurfaceFlinger should return specs; fallback only) |
| guest_no_vold_keys | 5 calls dropped (AIDL classes skipped) |
| skip_host_apex_services | applies (registers read from the code) |
| guest_power_guards | applies |
| guest_storage_guards | 37 calls dropped, UserDataPreparer, CE unlock, quota |
| guest_installd_abi | 25 identical, 2 remapped (fsverity 0x34/0x35 -> 0x35/0x36), 28 blocked |
| guest_no_sysrq_crash | applies (Watchdog.doSysRq is public final) |
| guest_no_weaver | applies (public final there; every weaver lookup goes through it) |
| guest_own_idmap | applies (name in p0) |
| guest_own_vold | 3 lookups -> "gvld", with vold_patched (docs/inplace-return.md) |
| oplus_wait_for_service --only DefragService | nothing to do |

Pixel's other system_server jars (`/system_ext/framework/yogi-services.jar`,
`malibu-plugin-provider.jar`) have no IInstalld, IVold, keystore maintenance,
recovery, reboot, weaver, gatekeeper or sysrq calls (dexdump).

## Overlays (scripts/make-overlays.sh)

oplus-services, ims-ext-common and oplus-telephony-common are patched only when
the image has them; Google's surfaceflinger has no QTI composer client and is
not patched; guards.txt lists services_patched.jar with vold_patched and
service.incremental_patched.so (the guest vold as "gvld", run 44,
docs/inplace-return.md); the 19 APEX payloads
come from Google's `com.google.android.*` modules (i18n and runtime are
`com.android.*`). The session requires oplus-services_patched.jar only when the
image has oplus-services.jar (guest_prepare.sh refuses otherwise).

## Session scripts

Changed for Pixel: guest mediaserver (`/system/bin/mediaserver`), no
MMListService wait, Pixel's setup wizards disabled and the provisioned keys set
before HOME (not with Google services presented, see below),
`com.google.android.factoryota` disabled, fold key
`fold_lock_behavior_setting stay_awake_on_fold_key`, ColorOS's brightness floor
skipped, sound names from product build.prop, Google APEX names in the payload
checks, Pixel folders in `ONEOS_GUEST_HIDE_GMS`. Everything else ColorOS-only
skips by itself (missing files).

## First run

1. `scripts/make-overlays.sh out/oneos-pixel-yogi.img out/overlays_pixel_yogi`.
2. `scripts/deploy-phone.sh --fresh --overlays out/overlays_pixel_yogi out/oneos-pixel-yogi.img`
   (the ColorOS stage moves to `oneos.old-<epoch>`; the app's Old
   stages lists it). `ONEOS_GUEST_HIDE_GMS=1` for the first run.
3. Do not set a screen lock in the guest: gatekeeper is still the phone's.

## Run 38 (2026-10-23, Fold, first Pixel session)

Black after the OneOS splash, back in One UI after about 2 minutes (the
auto-return's reboot). Pixel's SurfaceFlinger and boot animation ran on the
inner panel (the phone was folded, the cover stayed black); then the guest
zygote died preloading `android.app.SystemServiceRegistry`:
`NoClassDefFoundError: Class not found using the boot class loader`.

Cause: Pixel 17 has no `/system/framework/framework-telecom.jar`; android.telecom
is in its telephonycore module (framework-telecom.jar on the boot class path,
service-telecom.jar on the system server's). The guest got One UI's
telephonycore, which carries only framework-telephony.jar (One UI keeps telecom
as a platform jar), so `TelecomFrameworkInitializer` was missing
(SystemServiceRegistry.registerServices -> TelecomDependencies ->
DependencyAdapter.registerServiceWrapper). Nothing touched One UI's data: the
system server never started.

Fixes for the next run:
- make-overlays.sh stages the image's telephonycore, conscrypt and profiling
  when the image has no platform framework-telecom.jar (conscrypt and profiling:
  One UI's newer builds call ART and framework APIs Pixel's lack); guest_prepare.sh
  mounts them only when staged. ColorOS's images have the platform jar.
- guest_props.sh: the guest's `ro.audio.ihaladaptervendorextension_enabled`
  (Pixel: false) wins for the session; with One UI's true the guest audioserver
  waited forever for IHalAdapterVendorExtension/default.
- Pixel has no wificond: the switch restarts One UI's (free of One UI's client
  interface) and guest_start.sh no longer starts a missing binary.
- `ro.config.isolated_compilation_enabled=false` for every guest session: the
  guest's IsolatedCompilationService would drive One UI's composd.

Seen, not fixed: the boot animation draws only on the inner panel (unfold the
phone for a run); the guest runs with One UI's identity props (fingerprint,
model).

## Run 39 (2026-10-24)

Boot animation, then black, then the auto-return. The run 38 fixes all took
(telephonycore, conscrypt and profiling mounted; no audio extension wait;
isolated compilation off; One UI's wificond restarted). Zygote preloaded and
system_server started, then died in PackageManagerService.<init>:
`IllegalStateException: Unknown source permission
com.google.android.gms.permission.ACTIVITY_RECOGNITION in split permissions`
from `product/etc/permissions/split-permissions-google.xml`: only GmsCore
declares that permission, and the guest has no GmsCore (Pixel's is in a
product APEX the phone never activates; HIDE_GMS hides the folders).
guest_prepare.sh now binds such files without their gms splits when the guest
has no GmsCore APK.

## Run 40 (2026-10-24)

The split-permission fix took; system_server got through PackageManagerService
and died in startOtherServices: VirtualDeviceManagerService.onStart ->
LockPatternUtils.registerStrongAuthTracker on a null ILockSettings.
LockSettingsService had been rejected by the verifier a second earlier
(`initKeystoreSuperKeys ... [0x7] copyRes1 v4<- result0 type=Undefined`): the
storage guard dropped AndroidKeyStoreMaintenance.initUserSuperKeys but only
rewrote a following `move-result vN`, and Pixel's code reads it into p3. Same in
SyntheticPasswordCrypto.migrateLockSettingsKey. Fixed in
guest_storage_guards.py (any register; anything else after the dropped call
stops the build), and check-dex.py now also rejects any move-result that does
not follow an invoke or filled-new-array (the shipped N6 set passes).

## Run 41 (2026-10-24): Pixel boots

First Pixel session to boot to a usable UI (Settings in the large-screen two-pane
layout on the inner panel, the taskbar, the cover screen). On the phone: sound, SIM 1,
calls and call audio work; the cover screen works. Not working: Wi-Fi (the guest
WifiService's ISupplicantStaIface.addIface fails), SIM 2, apps that need Google
Play services (Messages, Credential Manager), first boot came up in another language than One UI's;
com.google.pixel.camera.services crash-looped ~9000 times (not Play services:
see below). Returned with Volume Up + Down after 350 s.
Screenshots: out/logs/run41-pixel/screenshots/.

Causes and fixes (for run 42):
- Wi-Fi: Pixel's wifi module picks the mainline supplicant (its RRO enables it,
  SDK 37, and the guest's wifi payload has bin/wpa_supplicant_mainline). One UI's
  init starts that daemon from One UI's own wifi APEX, in One UI's namespace,
  without Samsung's overlay conf; P2P lands on wlan0 and addStaInterface fails.
  guest_prepare.sh hides the binary in the guest's wifi payload, so the guest
  uses Samsung's ISupplicant/default like ColorOS (ONEOS_GUEST_MAINLINE_SUPPLICANT=1
  keeps it).
- Camera services loop: PersistentBackgroundCameraServices registers binder
  services only Pixel's VINTF declares; the shared servicemanager checks One
  UI's and throws, and the app is persistent. Hidden for the session.
- AppSearch: Pixel's ro.appsearch.feature.enable_isolated_storage=true makes it
  start a protected VM through One UI's virtualization module; false for the
  session.
- Language: a fresh stage now starts in One UI's current language
  (settings system_locales); One UI's persist.sys.locale is only its boot
  default (on this phone another language than the en-GB it shows).
- SIM 2: both slots came up; on a fresh stage Pixel chose the carrier's first
  APN (web1) for the LTE attach and no data SIM. One-time fix inside the
  guest: SIM 2 > Access point names > the carrier's second APN (web2), and pick the data
  SIM; the stage keeps both.
- Messages and Credential Manager need Google Play services (separate work).

## Run 42 (2026-10-24)

Second Pixel boot, fresh stage (out/logs/run42-pixel). Healthier than run 41:
3 crashes instead of 9069, no crash loop. Every guard either took effect or had
nothing to act on (FRP write discarded, no gatekeeper calls, app ids from 15000,
apexd calls skipped, weaver the phone's). Host state was unchanged at return,
Samsung's supplicant served Wi-Fi, and Bluetooth came on. On the phone: SIM 2 still
had no data, the status bar was off-centre, the screen froze briefly on task
switches, and Night Light turned the screen black.

Causes and fixes (for run 43):
- SIM 2: Pixel again attached the carrier's LTE with `web1` (its first APN,
  no preferred one on a fresh stage). SIM 2 left LTE 1.3 s later and stayed on
  3G. One UI and ColorOS attach with `web2`. Run 41's manual fix cannot work:
  by the time Settings runs, the modem has given up on LTE (we tried
  `web2` and went back to `web1`). Now oneos_switch.sh records One UI's
  preferred APN per slot while One UI runs (`host_pref_apn`). Before boot,
  guest_prepare.sh writes it into the guest TelephonyProvider's
  `preferred-full-apn` prefs (`apn/mcc/mnc<slot+1>`) and drops its saved
  `apn_id`. The provider then looks the APN up by those fields; the carrier's `web2`
  matches exactly one row. The UI activator logs each SIM's slot, sub and
  preferred APN. `ONEOS_GUEST_APN=0` keeps the guest's own. The guest has no
  IMS, and after Samsung's RIL restart One UI's IMS does not reach SIM 2 either
  (the IMS gate comment in oneos_switch.sh predates this), so SIM 2 calls
  fall back to 3G.
- Status bar: SystemUIGoogle leaves 20dp for the privacy dot at the end and
  only the corner padding at the start. The corner padding is the framework's
  0dp on Samsung's vendor partition (Pixel's own vendor overlay sets it). New
  static RRO `OneOSPixelStatusBar.apk` (switcher/overlays/pixel_statusbar):
  corner and dot padding 8dp, end padding 4dp also at sw600dp. It goes in the
  guest's `/oem/overlay` when the guest has SystemUIGoogle.
  `ONEOS_GUEST_STATUSBAR_PADDING=0` leaves it out.
- Freezes: SurfaceFlinger dropped to 1 Hz at every window transition (9 times,
  1-2.4 s each). The app-request render range was [0, 120]; ColorOS kept
  [120, 120]. guest_start.sh now pins both ranges of each panel to [60, 120]
  with SurfaceFlinger's transaction 1035. Its layout (i32 mode, i64 display,
  f min, f max) was checked in the image's surfaceflinger. While pinned,
  SurfaceFlinger ignores DisplayManager's policies, its thermal cap included.
  `ONEOS_GUEST_SF_PIN=0` leaves the ranges to DisplayManager.
- Night Light: the colour matrix stayed all zeros because
  `config_setColorTransformAccelerated` is false without Pixel's vendor
  overlay. guest_props.sh sets `persist.sys.overlay.nightmode=true` for the
  session (memory only), which enables the image's treble NightMode overlay.
  `ONEOS_GUEST_NIGHT_LIGHT=0` leaves it out.
- Bluetooth works at the stack level, but none of One UI's pairings were in the
  guest. Pairing a One UI device again in the guest would replace its key, and
  One UI's pairing would stop working (same Bluetooth address). Now One UI's
  `bt_config.conf` is copied one way into the stage at each switch.
  `ONEOS_GUEST_BT_IMPORT=0` skips the copy. Not checked yet: whether One UI keeps
  the link keys in plain text. Until a run shows the devices working, don't pair
  One UI's devices in the guest. (Run 43: the import as it was broke Bluetooth
  audio; see Bluetooth below.)
- Wi-Fi: wlan0 came up and scanned, but no network was joined (the stage had
  none saved). Pixel's wifi module drives nl80211 itself and never uses
  wificond, so the comments were corrected. New: verbose Wi-Fi logging, and a
  `diag_wifi` capture 45 s after Wi-Fi comes on.
- Seen, not fixed: all 224 packages failed first-boot dexopt (NullPointerException
  through One UI's artd, as in run 40). Pixel Launcher crashed once opening Home
  settings. The guest doesn't know about the cover's camera hole.

Check in run 43: "Guest attach APN" and "SF render rate pinned" lines, and a
"Bound guest /oem" line with OneOSPixelStatusBar.apk, in the takeover logs. In
all.log, the first `SET_INITIAL_ATTACH_APN [PHONE1]` should be `web2`, with no
`fps=1.00 Hz` lines. `cmd overlay list android` should show nightmode `[x]`.

## Run 43 (2026-10-24)

Calls on SIM 2 (out/logs/run43-pixel): silent both ways on earpiece, speaker
and mic, as in runs 41 and 42 (SIM 1 calls worked in run 41).

Cause: the call ran on SIM 1's ADSP voice session. Samsung's audio HAL picks it
from the vendor key `g_call_sim_slot` (0x01 VSID_1, 0x02 VSID_2, 0x10 swap
during a call), and a restarted HAL starts on VSID_1. The call keeper sent
0x10 through the guest AudioFlinger and got `Result: Parcel(00000000)`, but the
HAL never logged `processSetVendorParameters: configured g_call_sim_slot`
(09:09:33; the same silent drop in run 41 at 04:17:46 and run 42 at 06:36:39
and 06:37:22). Pixel's libaudiohal@aidl passes unknown keys to
`IModule.setVendorParameters` only through an `IHalAdapterVendorExtension`.
With `ro.audio.ihaladaptervendorextension_enabled=false` (guest_props.sh, run
38, which the Pixel audioserver needs to start) it has none, and it drops the
key without a log line. One UI's and ColorOS's QTI audioservers load the
extension (`libaudiohalvendorextn.so`) in-process, which is why ColorOS's keys
arrived (run 34).

Fix (guest_start.sh call keeper):
- While the property is not true, the keeper sends the key straight to the
  shared HAL: `service call android.hardware.audio.core.IModule/default 34`,
  with the parcel the QTI extension builds (a `VendorParameter` whose ext is a
  `qti.audio.core.VString`; 136 bytes for one key, the layout ColorOS's
  audioserver sent in run 34). ColorOS keeps the AudioFlinger path.
  `ONEOS_GUEST_CALL_SIM_VIA=af|hal` forces the first path.
- Every send is checked against the HAL's own log: the last `configured
  g_call_sim_slot` line from the HAL's pid, before and after, looked at again
  1 s later before a send counts as lost (a late line must not move the keeper
  to a path that drops the key). The line also gives the HAL's VSID state
  afterwards (`HAL state I2`, `A2`, ...; `none` until the HAL logs one).
- Nothing is sent on a path that is not ready yet: the AudioFlinger path waits
  for the guest audioserver to register (before, an idle send on ColorOS could
  go out before that and be lost); the keeper tries again on its next turn.
- Path check as soon as the HAL is known, before any call: 0x01 (a restarted
  HAL is on VSID_1 anyway), or the last call's SIM. If it is not delivered,
  the keeper tries the other path once, and only with an idle 0x01/0x02. It
  never resends a 0x10, because a late first copy plus a second one would
  swap back.
- At start the keeper also logs where the HAL gets `VString`, whether the HAL
  library imports `AIBinder_getCallingUid`, and `bluetooth.sco.managed_by_audio`.
- Nothing persistent changes: the HAL's in-memory VSID, which One UI's
  audioserver sets again before every call, and the return restarts the HAL.

Run 43 had no in-call Bluetooth test: the only call (28 s) was
routed `call-handset` before any Bluetooth link existed, and the car kit never
connected (see Bluetooth, run 43). The Bluetooth media failure at 09:14:26
(`[bluetooth]` module, `openOutputStream` for bt-a2dp) is a separate issue.

Check in run 44: in call_audio.log, `g_call_sim_slot path hal first`, then
`g_call_sim_slot=0x01 via hal (path check, ...)` or `0x02 ...` with
`HAL got it` (for 0x02 also `HAL state I2`), before any call. For an outgoing
and an incoming call on SIM 2, `call on SIM slot 1: HAL A2` (or `I2`) and
audio both ways.
For a SIM 1 call after a SIM 2 call, a 0x10 and `HAL A1`. Also check the
speakerphone: One UI also sends `l_dual_speaker_amp_left_power_enable=true` at
call start, and Pixel never does.

If the `via hal` sends log `HAL NEVER got it` (on Pixel the AudioFlinger
path cannot help, so `on either path` may follow or not), the next steps, in
order:
- F1: if the `service call` Result is `00000000` and the HAL still logs no
  `configured` line, the HAL may check the caller uid (the keeper is root, an
  audioserver 1041; see the `AIBinder_getCallingUid` count). Retry the same
  call by hand as uid 1041 through the root manager's su, during the session
  and with no call running (0x01 is idle and harmless), before building
  anything: `su 1041 -c "service call android.hardware.audio.core.IModule/default
  34 i32 1 i32 1 i32 124 s16 g_call_sim_slot i32 1 i32 1 i32 72 s16
  qti.audio.core.VString i32 20 s16 0x01 i32 0"`, then look for the HAL's
  `configured g_call_sim_slot` line.
- F2 (not built): a small native helper in the guest that loads the QTI
  `libaudiohalvendorextn.so` and registers its `IHalAdapterVendorExtension`
  before the guest audioserver starts. The session would then keep One UI's
  `true`, and the guest audioserver would forward every vendor key itself.

## Google services

Pixel 17 has GmsCore only in `/system/product/apex/com.google.android.gmssystem.prodvic.apex`,
which the phone's apexd never activates ("Missing required system package:
com.google.android.gms"). make-overlays.sh stages that APEX's payload as
`gmssystem_payload.img` (EROFS, 155 MB, GmsCore 26.18.43 inside; deploy-phone.sh
pushes it like the other payloads). The image is not changed; for the session
guest_prepare.sh:

- loop-mounts the payload at the guest's `/apex/com.google.android.gmssystem`
  and its `@<version>` dirs (the guest no longer sees One UI's module there);
- binds a small read-only tmpfs package folder (`$STAGE/gmscore`, labelled
  `system_file`: the payload's `PrebuiltGmsCoreVic.apk` plus the image's own
  `app_chimera` and `m` Dynamite modules) over
  `/system/product/priv-app/PrebuiltGmsCore`, so GmsCore is a plain product
  priv-app as before Android 17 (the image's product XMLs already allowlist its
  privileged permissions and user types), and hides the payload's own
  `priv-app` so it is found once;
- writes `data/oneos_gms_present`. GSF, the Play Store and the setup/restore
  apps are the image's own folders.

Setup: with Google services presented nothing sets `device_provisioned` /
`user_setup_complete` or disables Pixel's setup wizards, so on a stage that was
never set up Pixel's setup wizard runs at first boot. Without an account, skip
sign-in, and skip screen lock, fingerprint and face (the gatekeeper guard
refuses a guest screen lock). When it finishes it sets both keys and turns
itself off; the stage keeps that and later sessions go straight home. A stage
the activator already marked set up (runs 41-42: keys forced, wizards
disabled) stays that way: the wizard needs a fresh stage. A session with
`ONEOS_GUEST_GMS=0` marks setup done as before.

Switches: `ONEOS_GUEST_GMS=1` (default; presents GmsCore when the payload is
staged) or `0`. Refused (switch preflight, or guest_prepare.sh) together with
`ONEOS_GUEST_HIDE_GMS=1`, when guards.txt lacks the gatekeeper, pdb, apexd and
appid15000 guards, and on a stage whose packages.list has app uids
10000-14999 (made before the app id guard: `deploy-phone.sh --fresh`).

Not covered yet: signing in (not tried; the guest still uses the phone's
gatekeeper, FRP partition and keystore2, with writes to the first two refused
and keystore isolation now by guest app ids from 15000 plus no guest users or
profiles); Play's module updates (refused by the apexd guard); dexopt of
GmsCore (artd resolves paths in One UI's namespace, so it probably runs
JIT-only). The OneOS app (0.3.7) stages the payload with the rest of its
`pixel-f8u` set and never offers to hide Google services for this image.

## Bluetooth (run 43)

Run 43 was the first session with One UI's pairings imported. A pair of
Bluetooth earbuds connected, showed as the output and played nothing, and calls
never reached them. The car kit never connected at all (no link to it in the whole session);
the test call happened before any Bluetooth link existed.

- One UI's LE keys are not in AOSP's layout. Each device's `LE_KEY_PID` (AOSP:
  16-byte IRK, address type 00 or 01, identity address) decoded to type 149, 181
  or 13 and a made-up identity address (the earbuds' `xx:xx` -> `yy:yy`). The Pixel
  stack used it anyway, in Java and in the native stack: A2DP's active device,
  HFP and even the earbuds' pairing record went to `yy:yy`. So no A2DP session
  started ("SetActivePeer: Error setting ..."; PAL `-19` on every write while
  audio was routed to "BT A2DP Out"), HFP never got its service-level
  connection ("CindResponse: Invalid index -1") and SCO was refused.
- The imported link key was rejected too: the earbuds' first connection dropped
  with `HCI_ERR_AUTH_FAILURE`, before any pairing in the guest. One UI may keep
  its keys in another form (not known yet).
- After that the earbuds asked to pair again, and the guest's stack accepted it
  by itself ("Auto-accept temporary pairing", no dialog). That gave the earbuds a
  new key for this phone: **the earbuds need pairing again in One UI after run
  43.**

Fix (guest_prepare.sh 5c2): the stage's `bt_config.conf` is now One UI's file
through a filter. If any device's `LE_KEY_PID` is not AOSP's layout with the
device's own address, every device loses its LE records (`LE_*`, `Le*`,
`AddrType`, `ServiceLe`, GATT, LE Audio, CSIP, hearing aid, VCP), LE-only
devices are dropped and dual-mode ones become BR/EDR (`DevType` 3 -> 1); the
BR/EDR link keys stay. An AOSP-layout file passes unchanged. If the filter
fails, or its output has no `[Adapter]`, the guest starts with no pairings
(never an unchecked copy, never run 43's stage file). One UI's file is only
read. `ONEOS_GUEST_BT_LE_SANITIZE=0` imports it unchanged;
`ONEOS_GUEST_BT_IMPORT=0` keeps the guest's own file, which goes through the
same filter (the stage's file from run 43 still holds One UI's bad LE data; a
file the Pixel stack wrote from its own pairings passes unchanged). The same
happens when One UI has no `bt_config.conf`.

`$STAGE/diag_bt/` (pulled with the other `diag_*`): `shape_oneui.txt` and
`shape_guest_prev.txt` (the file the Pixel stack itself wrote last session)
list key names, value lengths, hex or not, the `DevType`, `AddrType`,
`LinkKeyType` and `*PairingAlgorithm` values and, for `LE_KEY_PID`, byte 16
and where the device's own address sits (`self_at`, `self_rev_at`; AOSP's
layout gives 17 and -). No key values; devices only by the last two bytes of
their address. `sanitize.txt` has the filter's summary, also in the takeover
log ("Guest Bluetooth pairings: ..."). Each diagnostics round
(`diag_20/40/90/final`) also has `bluetooth_manager.txt`, the guest's `dumpsys
bluetooth_manager` (active devices, bond states, identity addresses).

Rule: **never pair One UI's devices in the guest.** Both systems use the
phone's one Bluetooth address, so a new pairing replaces the device's key and
One UI's pairing with it stops working. Keep other paired devices (the car) off
or out of range until a run shows imported keys working.

Run 44 test:

1. In One UI, before switching: pair the earbuds again and play about 10 s on
   them (One UI's reference lines in all.log); switch the car kit off or keep
   it out of range for the whole session.
2. In the guest: take the earbuds out of the case. Don't pair; cancel any pairing
   dialog. If they connect: 30 s of media, then one SIM 2 call on the earbuds.
3. Pass: the earbuds' first connection (`xx:xx`) has no `HCI_ERR_AUTH_FAILURE`, "Bond
   loss" or "Auto-accept temporary pairing"; no "Unexpected identity address
   type"; `setActiveDeviceNative: xx:xx:xx:xx:xx:xx` (the earbuds) with no SetActivePeer
   error; no "has NO session" or "pal stream open failed"; HFP's
   ConnectionStateCallback reaches 3 with no "CindResponse: Invalid index".
4. After the return: the earbuds still play in One UI without pairing again.
5. If the first connection fails with `HCI_ERR_AUTH_FAILURE` again, imported
   link keys are unusable: make `ONEOS_GUEST_BT_IMPORT=0` the default, pair the
   earbuds again in One UI, and compare the two shape files. Try the car kit only
   after the earbuds pass.

Bluetooth lines in all.log:

```sh
grep -E 'HCI_ERR_AUTH_FAILURE|Bond loss|Auto-accept temporary|Unexpected (identity|pairing)|SetActivePeer|setActiveDeviceNative|CindResponse|ConnectionStateCallback|AG open failed|has NO session|startSession|bt_offload state|BT device .* not (connected|ready)|pal stream open failed' all.log
```

Not done: a smali patch of the Bluetooth APEX (the native pairing record had
the wrong address too, so a Java fix is not enough); A2DP offload off and a
full HCI snoop log (both props One UI probably defines too, so only through
guest_props.sh's override path; a snoop log holds One UI's link keys). Not
explained yet: "bt_offload state is disabled" (AHAL_Platform_QTI, 09:14:26),
and Settings not listing the connected earbuds as a media device (the imported
CSIP group is suspected; the filter removes it).

## Signal bars (run 43)

Both SIMs showed empty bars for the whole session, as ColorOS did in run 34.
Samsung's RIL accepted the guest's indication filter (127) and signal reporting
criteria and kept sending other network indications (network state, cell info,
PCC, LCE), but never currentSignalStrength. AOSP's SignalStrengthController asks
for the signal strength only at radio-on and, for opportunistic subscriptions
only, in getSignalStrength(). So the guest kept the all-invalid values read
right after RADIO_POWER on (09:07:37), level 0 on both SIMs. TelephonyRegistry
in diag_90 had level 0 while Phone 1's own cell info had LTE rsrp -99, level 2.
A GET_SIGNAL_STRENGTH does return real values (09:07:34, 09:07:35). The likely
gate is Samsung's screen state, which One UI sets with OEM_HOOK_RAW
(`1182000D...`) on every screen change; sending that would change the shared
modem's state, so it is not used.

The "!" on the bars was mostly not a badge. Pixel stacks both SIMs in one icon,
the primary as bars over the secondary as dots, and at level 0 that looks like a
row of "!". The real cutouts were SIM 1's "Emergency calls only" while the modem
answered REG_DENIED_EM after the RIL handover (09:07:40 to 09:08:50, REG_HOME
UMTS at 09:09:30), and every icon while setup ran with no default data
subscription. Both are radio and APN follow-ups, like SIM 2's
NO_SUITABLE_DATA_PROFILE.

Fix (run 44): AOSP's own TelephonyComponentFactory injection, not a patched
Google dex (telephony-common.jar is on the boot class path and in the boot
image). make-overlays.sh builds:
- `oneos-telephony.jar`: OneOSTelephonyComponentFactory makes the image's
  SignalStrengthController and starts OneOSSignalPoll on it, which sends the
  controller its own EVENT_POLL_SIGNAL_STRENGTH every 20 s while the screen and
  the radio are on (a read-only RIL query; the ColorOS helper made generic). An
  exception from the poll never reaches GsmCdmaPhone's constructor.
- `OneOSPhoneInjection.apk` (switcher/overlays/phone_injection): a static RRO
  for com.android.phone's `xml/telephony_injection` (empty in the image) that
  names the class and `/system_ext/framework/oneos-telephony.jar`.
- `oneos-telephony.sha256`: the telephony-common.jar and TeleService.apk it was
  checked against.

It builds only when the image has both files, is not ColorOS, has an empty
injection config, TeleService declares no overlayable resources, none of the
image's RROs for com.android.phone (9 here) overrides that config, and
`patches/aosp_signal_poll_check.py` finds every class, member and message
handler the jar relies on. guest_prepare.sh then checks the two hashes, puts the
jar in a read-only overlay layer on the guest's system_ext (the injection drops
a jar on a writable mount; the same tmpfs layer still carries the apex mount
point) and the RRO in the guest's `/oem/overlay`. `ONEOS_GUEST_SIGNAL_POLL=0`
leaves both out. The app's `pixel-f8u` set (0.3.7) carries these three
files.

Check in run 44: "Read-only overlay on .../system/system_ext" and "Signal poll:
TelephonyComponentFactory injection" in the takeover log, `OneOSPhoneInjection.apk`
in the "Bound guest /oem" line. In all.log: `TelephonyComponentFactory: validated
paths: /system_ext/framework/oneos-telephony.jar`, `Total components injected:
1` (the jar path passed; only the two `OneOSSignalPoll` lines show that the
class loaded), then `> SIGNAL_STRENGTH` for each SIM about every 20 s with the
screen on, no FATAL EXCEPTION in com.android.phone and no second "RIL Restart
for Phone Died". `validated paths: null` means the RRO was not applied; an
empty list means the jar was missing or on a writable mount;
"Injection jar is not protected , path: ..." means statvfs on it failed (no
jar, or SELinux kept the radio domain from the overlay mount); `failed:` or
`injection failed:` means the class did not load; "OneOSSignalPoll: stopped"
means the phone's controller was not the one the poll started on. diag_40 and
diag_90 have `overlays_phone.txt` (the RRO's state, the jar's label and the
guest's system_ext mounts, com.android.phone's pid, the injection lines) and
`signal_polls.txt` (every SIGNAL_STRENGTH request and answer still in the radio
log); `telephony_registry.txt` should then show mSignalStrength levels that
match mCellInfo.

## Open (not seen on a phone yet)

- `/product/overlay/*__yogi__*` RROs carry the Pixel Fold's display config
  (cutout, corners, fold), the Pixel form of the camera-hole mismatch.
- The guest's brightness path on Samsung's panel (ColorOS needed
  samsung_backlight_curve.py in oplus-services).
- `guest_props.sh recover` puts back only the language and OPPO's
  `persist.oplus.*` after a hard reset: the other persist props a Pixel
  session saved (runs 38-43's returns deleted 22-28 added ones; which, the logs
  do not say, possibly Phenotype's `persist.device_config.*`) stay in One UI.
  The app's Restore says "restored the language only" for a Pixel stage (0.3.7);
  widening `guest_prop()` needs a Pixel session's persist diff from the phone.
- The app (0.3.7) offers the image on the Fold with its `pixel-f8u` set
  (app-android/README.md); no session has been started from the app yet.
