# Play services and the Play Store in ColorOS guests

Since 0.4.16, every ColorOS set on every phone gets One UI's own Play services
and Play Store app files (`switcher/assets/guest_google.sh`). Pull request #1
did this first for the Z Fold8 with the Find N6 set.

## Why

ColorOS 17 keeps Play services in a Google APEX,
`product/apex/com.google.android.gmssystem.prodvic.apex` (its payload holds
`priv-app/PrebuiltGmsCoreVic`). The phone's apexd never activates an APEX
from the guest image. `my_product/priv-app/GmsCore` holds only the modules
folder `m`, with no APK. This is the same in the Find N6 CN, Find X9 Ultra CN
and Find X9 Ultra IN images.

Section 9 of guest_prepare.sh binds One UI's `/apex` folders into the guest,
One UI's gmssystem APEX among them, so the guest found One UI's GmsCore there.
Its package manager did not give it Play services' privileged permissions.
A Galaxy S25 with the X9U IN build (0.4.15) showed:

- Play services: `SecurityException: You either need MANAGE_USERS or
  CREATE_USERS permission to: query users` (UsageReportingChimeraService,
  26.42.30).
- Play Store: `Failed to find provider com.google.android.gsf.gservices`.
  None of these images ships Google Services Framework.

The CN images' Play Store slot, `my_product/priv-app/GooglePlayServicesUpdater`,
is a 2022 stub (22.7.54). The IN image has a current Play Store in
`my_product/priv-app/Phonesky`.

## What it does

- **Before One UI stops** (`oneos_switch.sh` → `guest_google.sh snapshot`):
  - It runs `pm path` for `com.google.android.gms` and `com.android.vending`.
    Only APKs under `/data/app`, `/product`, `/system`, `/system_ext` or One
    UI's gmssystem APEX are accepted.
  - It copies the APK files into `$STAGE/google_apps/<GmsCore|Phonesky>`,
    with the arm64 libraries from the same APKs and sha256 lists. App and
    account data are never copied.
  - When One UI's files are unchanged (same path, size and time), the copy
    is kept as it is.
- **In guest_prepare.sh** (`guest_google.sh prepare`):
  - It checks both copies against their sha256 lists.
  - It binds GmsCore read-only over `priv-app/GmsCore` in `my_product`, or
    else in the guest's product folder, with the image's own `m`. The Play
    Store goes over the CN stub next to it. The IN image keeps its own Play
    Store.
  - It removes the guest's own installed copy of Play services: the
    `com.google.android.gms-*` folders in the guest's `/data/app`, the APK
    folders only, never the package's data (0.4.17). Nothing may be mounted
    under the guest's `/data/app` at that point; links are not followed.
  - It writes `google_apps/active`.
- **After section 9:** with `active` written, an empty read-only tmpfs covers
  `priv-app` in the guest's gmssystem APEX binds, after they are made
  private. The guest then finds Play services once, in that folder. The
  image's own privapp permissions file (ColorOS:
  `privapp-permissions-google-product.xml`) grants its privileged
  permissions there.

## Why the installed copy goes (0.4.17)

A Galaxy S25 with the X9U IN build on 0.4.16 got too hot to hold. The copy
and the APEX hiding worked, but ColorOS ran Play services 26.42.30 from a copy
installed in the guest's own `/data/app` (from before 0.4.16, or an app
store), not from the system copy. That copy had no privileged permissions
(`You either need MANAGE_USERS or CREATE_USERS permission`), and it crashed
720 times in 7 minutes. Every restart is a new process compiled in memory
(`-Xforcejitzygote`), so the CPU never rested: skin 43 °C, 5 % battery in
7 minutes. With the installed copy gone, the package manager takes the system
copy and keeps the package's data.

## When it adds nothing

The switch goes on as before 0.4.16 in each of these cases, with a
`Google apps:` line in the session log:

- A set that is not ColorOS. Pixel sets present the image's own payload
  (guest_prepare.sh, GMS_WANT).
- A launch with `ONEOS_GUEST_HIDE_GMS=1`.
- guards.txt lacks one of the gatekeeper, pdb, apexd or appid15000 guards.
- The guest data has app ids 10000-14999 from before the app-id guard.
  Setting up again with fresh guest data turns it on.
- A package is missing, an APK path is unexpected, a link is below the copy,
  copying fails (space), or the checksums do not match.

0.4.14 and 0.4.15 refused the switch for some of these cases on the Z Fold8.
The Fold8 copy in `$STAGE/fold8_google` is removed.

## Tests

`scripts/test-google-snapshot.py`: library extraction and manifests with real
ZIPs, rejected changed libraries and links, and every no-op case above
(exit 0, no copy, no `active`).
