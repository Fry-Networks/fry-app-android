# App-only Compose test tags

`PROTOCOL.md` section 10 lists the provisioning-flow tags and must stay byte-identical with the
copy in `fry-firmware`. Tags introduced by the miner-management feature live here instead.
Every tag is also exposed as a resource-id (`testTagsAsResourceId = true`), so UI Automator
scripts can address nodes by these exact names.

Scheme: `<screen>_<element>[_<dynamicKey>]`.

## Navigation
- `nav_home`, `nav_scan`, `nav_settings` (existing), `nav_miners`
- `wallet_bridge_host` — the 1 dp invisible signing-bridge WebView container

## Sign-in (`ui/account`)
- `signin_connect_pera`, `signin_connect_defly` — wallet buttons (disabled when not installed)
- `signin_progress`, `signin_status` — in-flight step text
- `signin_open_wallet` — re-fires the last wallet deep link
- `signin_profile_form`, `signin_email`, `signin_first_name`, `signin_last_name`, `signin_submit`, `signin_profile_cancel`
- `signin_error`, `signin_error_dismiss`

## Miners tab (`ui/miners`)
- `miners_root`, `miners_placeholder`
- `account_address`, `account_signout`
