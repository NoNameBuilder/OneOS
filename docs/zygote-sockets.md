# The guest zygote's sockets

The guest's `/dev` is a bind of One UI's, so `/dev/socket` is shared. The
guest's `zygote_exec` (`switcher/native/zygote_launcher.c`) creates
`/dev/socket/zygote` and `/dev/socket/usap_pool_primary`. One UI's init uses
the same two paths for its own zygote, which OneOS keeps stopped for the whole
session (oneos_switch.sh [4b/6]).

## What went wrong (S24 Ultra, IN build, 0.4.14 and 0.4.15)

Two people booted the IN build on a Galaxy S24 Ultra; a third never got past
the boot screen. His phone ran LSPosed's Vector daemon with NeoZygisk:

1. The guest's zygote made its sockets (18:35:56).
2. 20 s later the daemon logged `Failed to inject VectorService into
   system_server` / `Restarting system_server...` and restarted One UI's
   zygote. init made One UI's sockets at the same two paths, over the
   guest's files.
3. The loop of [4b/6] stopped One UI's zygote. When init reaped it, it
   unlinked both socket files, as it does for every service socket.
4. The guest's system_server got `ZygoteStartFailedEx: Error connecting to
   zygote ... No such file or directory`. No app ever started, and the
   failsafe returned to One UI after 600 s. The WebView crash fixed in 0.4.14
   was a symptom of the same thing.

## The fix (0.4.16)

- **oneos_switch.sh** (`protect_zygote_sockets`, in the background after the
  guest launch) waits until the takeover process has turned into
  `app_process64`, which happens after zygote_exec has made both sockets. It
  then binds each socket file onto itself in init's mount namespace. On Linux,
  a file that is a mount point in the caller's namespace cannot be unlinked
  (EBUSY). So init can neither remove the guest's sockets nor make its own
  over them. A restarted One UI zygote starts without them, and the [4b/6]
  loop stops it as before. The guest's mount namespace does not see the
  binds, and connecting to the sockets works the same.
- **return_to_oneui.sh** takes both binds off, in init's namespace, right
  after it kills the guest's processes and before anything starts One UI's
  zygote. If a bind stays, it takes the reboot return. A reboot drops the
  binds anyway.
- Diagnostics (`webview.txt`) list the `/dev/socket/` mounts of init's
  namespace.

Only One UI's zygote is affected while the binds are in place. Nothing else
in One UI uses those two paths.
