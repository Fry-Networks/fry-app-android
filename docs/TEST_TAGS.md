# App-only Compose test tags

`PROTOCOL.md` section 10 lists the provisioning-flow tags and must stay byte-identical with the
copy in `fry-firmware`. Tags introduced by the miner-management feature live here instead.
Every tag is also exposed as a resource-id (`testTagsAsResourceId = true`), so UI Automator
scripts can address nodes by these exact names.

Scheme: `<screen>_<element>[_<dynamicKey>]`. `<key>` is the full miner key (e.g.
`FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345`), `<n>` a 0-based row index unless stated, `<no>` the
dashboard reward number, `<FAMILY>` a `MinerFamily` enum name (`FEM`, `RDN`, `IHAQM`, …),
`<status>` a lowercase `MinerStatus` name (`active`, `pending`, `unregistered`, `migrated`,
`not_on_dashboard`), `<kind>` one of `registration` / `node` / `verification`.

## Navigation
- `nav_home`, `nav_scan`, `nav_settings` (existing), `nav_miners`
- `wallet_bridge_host` — the 1 dp invisible signing-bridge WebView container
- Routes: `miners`, `miner/{minerKey}`, `miner/{minerKey}/rewards`, `keys`, `activity`

## Sign-in (`ui/account`)
- `signin_connect_pera`, `signin_connect_defly` — wallet buttons (disabled when not installed)
- `signin_progress`, `signin_status` — in-flight step text
- `signin_open_wallet` — re-fires the last wallet deep link
- `signin_profile_form`, `signin_email`, `signin_first_name`, `signin_last_name`, `signin_submit`, `signin_profile_cancel`
- `signin_error`, `signin_error_dismiss`

## Miners tab (`ui/miners/MinersScreen.kt`)
- `miners_root` — screen root (the pull-to-refresh box when signed in; the sign-in column when signed out)
- `miners_loading`, `miners_empty`, `miners_empty_add_device`, `miners_error`, `miners_error_message`, `miners_error_retry`
- `miners_snackbar` — session-expired / device-mismatch snackbar host
- `miners_open_keys`, `miners_open_activity` — header actions
- `miners_totals` — totals card; `miners_online` — fleet `x / y online`
- `miners_totals_fnode`, `miners_totals_tfry` — per-asset bucket columns, each with
  `_claimable`, `_pending`, `_accruing`, `_claimed` suffixes (e.g. `miners_totals_fnode_claimable`)
- `miners_next_unlock`, `miners_next_claimable` — countdowns (absent when the dashboard sends no date)
- `miners_search` — search field; `miners_sort` — sort menu button; `miners_sort_<status|family|name|claimable>` — menu items
- `miners_filter_<status>` — status chips; `miners_filter_<FAMILY>` — family chips (only families present)
- `miners_list` — the LazyColumn; `miners_count` — `x of y miners`; `miners_no_match` — filtered-to-nothing line; `miners_inline_error`
- `miners_item_<key>` — row (click opens `miner/<key>`); `miners_key_<key>`, `miners_status_<key>` (status chip),
  `miners_family_<key>` (family chip), `miners_claimable_<key>`

## Miner detail (`ui/miners/detail`)
- `miner_root`, `miner_loading`, `miner_error`, `miner_error_retry`, `miner_empty`, `miner_content`, `miner_inline_error`
- `miner_title`, `miner_back`, `miner_refresh`, `miner_snackbar`
- Identity card `miner_identity`: `miner_nickname`, `miner_key` (full key), `miner_copy_key`, `miner_family`, `miner_status`,
  `miner_product`, `miner_registered`, `miner_verified`, `miner_is_active`, `miner_reward_eligible`, `miner_reward_block_reason`,
  `miner_virtual`, `miner_byod`, `miner_reward_wallet`
- Rewards card `miner_rewards`: `miner_reward_claimable`, `miner_reward_pending`, `miner_reward_accruing`, `miner_reward_claimed`,
  `miner_next_unlock` (countdown), `miner_open_rewards` (History)
- Stake card `miner_stake`: `miner_stake_<kind>_amount`, `miner_stake_<kind>_time`, `miner_stake_<kind>_txid`,
  `miner_stake_<kind>_tier`, `miner_stake_<kind>_lock` (countdown while locked)
- Hardware card `miner_hardware` (hidden for virtual miners / non-hardware families): `miner_hw_status`, `miner_hw_linked`,
  `miner_hw_valid`, `miner_hw_mac_match`, `miner_hw_reason`
- Actions row `miner_actions`: `miner_action_claim` (enabled when claimable > 0), `miner_action_stake_registration`
  (enabled when unregistered and the product has a registration tier), `miner_action_stake_node` (present for node families only),
  `miner_action_stake_verification`, `miner_action_withdraw` (menu: `miner_withdraw_registration`, `miner_withdraw_node`,
  `miner_withdraw_verification`, each enabled by `withdrawable` + lock), `miner_action_rename`, `miner_action_reward_wallet`
- Rename dialog: `rename_dialog`, `rename_input`, `rename_save`, `rename_cancel`, `rename_error`
- Reward-wallet dialog: `rewardwallet_dialog`, `rewardwallet_input`, `rewardwallet_invalid`, `rewardwallet_save`,
  `rewardwallet_cancel`, `rewardwallet_error`
- Withdraw dialog: `withdraw_dialog`, `withdraw_confirm`, `withdraw_cancel`, `withdraw_error`

## Claim sheet (`ui/miners/claim`)
- `claim_sheet` (modal), `claim_content`, `claim_miner`
- `claim_stages`, `claim_stage_<n>` — `<n>` is 1-based over: wallet, opt-in, preview, balance, fee, envelope, sign, confirm, done;
  the node's contentDescription ends with `: done | current | pending | failed`
- `claim_totals`, `claim_total_<assetId>` — preview amounts
- `claim_optin` — opt-in button (state OptInRequired); `claim_reconnect` — reconnect-wallet button
- `claim_wallet_prompt`, `claim_txn_summary` (native "You are about to sign" line), `claim_open_wallet`
- `claim_countdown` — 300 s signature deadline
- `claim_confirm` (enabled only on the Preview step), `claim_cancel` (disabled during PayingFee / AwaitingSignature / Confirming)
- `claim_error`, `claim_error_message`, `claim_retry`; `claim_success`, `claim_txid` (explorer link), `claim_done`

## Stake sheet (`ui/miners/stake`)
- `stake_sheet` (modal), `stake_content`, `stake_miner`, `stake_loading`
- `stake_tier_one`, `stake_tier_two` — verification tier chips (verification context only)
- `stake_stages`, `stake_stage_<n>` — 1-based over: wallet, amount, balances, precheck, sign, submit, verify, record, done
- `stake_plan`, `stake_amount`, `stake_usd`, `stake_asset`
- `stake_optin`, `stake_reconnect`, `stake_wallet_prompt`, `stake_txn_summary`, `stake_open_wallet`
- `stake_confirm` (enabled on Ready), `stake_cancel` (disabled during sign / submit / verify / record)
- `stake_error`, `stake_error_message`, `stake_retry_after` (429 Retry-After seconds), `stake_retry`; `stake_success`, `stake_txid`, `stake_done`

## Rewards history (`ui/miners/rewards`)
- `rewards_root`, `rewards_back`, `rewards_loading`, `rewards_empty`, `rewards_error`, `rewards_error_retry`, `rewards_inline_error`
- `rewards_counts`, `rewards_count_weekly`, `rewards_count_daily`, `rewards_count_total`
- `rewards_list`, `rewards_item_<no>`, `rewards_status_<no>`, `rewards_amount_<no>`, `rewards_original_<no>` (strike-through when on hold),
  `rewards_tx_<no>` (explorer link), `rewards_claim_<no>` (claimable rows only; opens the claim sheet with `no`)
- `rewards_page_prev`, `rewards_page_next`, `rewards_page_label`

## Miner keys (`ui/miners/keys`)
- `keys_root`, `keys_back`, `keys_loading`, `keys_empty`, `keys_error`, `keys_error_retry`
- `keys_group_<FAMILY>`, `keys_item_<key>`, `keys_key_<key>`, `keys_copy_<key>`, `keys_registered_<key>`
- `keys_cred_<key>_<portal>` — credential block (masked by default); `keys_cred_reveal_<key>_<portal>` — reveal/hide toggle
  (auto-masks after 15 s); `keys_cred_value_<key>_<portal>_<field>` — masked (`••••••••`) or revealed value
- `keys_byod_header`, `keys_byod_<n>`, `keys_byod_text_<n>`, `keys_byod_copy_<n>`

## Activity (`ui/miners/activity`)
- `activity_root`, `activity_back`, `activity_loading`, `activity_empty`, `activity_error`, `activity_error_retry`
- `activity_item_<n>` (≤ 12 rows), `activity_type_<n>`, `activity_amount_<n>`, `activity_time_<n>` (relative time)

## Settings account card (`ui/settings`)
- `settings_account` (shown only when signed in), `account_address` (full address, copyable via `account_copy_address`),
  `account_vendor` (Pera / Defly chip), `account_fingerprint` (device bound / not bound)
- `settings_signout`, `settings_rebind_fingerprint`, `settings_snackbar`
- Existing: `settings_wallet`, `settings_link_dashboard`, `settings_link_discord`, `settings_link_docs`, `settings_about_body`, `settings_app_version`

## Device detail (`ui/device`)
- `device_open_miners` — "Open in Miners" (shown only when signed in; navigates to `miner/<key>`)
- Existing: `device_minerkey`, `device_claim_link`
