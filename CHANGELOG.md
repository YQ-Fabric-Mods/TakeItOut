# Changelog

## 1.1.29-26.3-origin-return

- Replaced full-inventory swaps and marked dump containers with a single return-to-source path.
- Record every successful world-container and carried-shulker take on the server for the current session.
  The oldest available material is returned to its recorded source when another material needs space.
  Matching stacks are aggregated across the 36 main inventory slots, including the main hand; ordinary
  materials receive no per-stack tags. Items with different components remain distinct.
- Carried source shulkers have a stable identity so moving them between inventory slots cannot redirect
  returns. Tracked whole shulkers can also return to their world source after their contents change.
  Nested shulkers and returning a box while extracting its contents are prevented.
- Unknown items stay in the inventory. Missing, unloaded, disallowed, ambiguous or full sources are
  skipped; when no complete stack can return, extraction is cancelled and its source restored.
- The former dump hotkey now returns tracked items to their sources. Removed dump marking, its UI and
  its storage code. Old dump configuration files are unused and are not deleted.
- Provenance is session-only and clears on disconnect or server shutdown. The server must run this
  build; original 26.3 clients retain the same packet format, while updated clients also get the revised
  return UI. Remote extraction retains the branch's existing chunk-loading behavior;
  returning to an unloaded world source is skipped.
- Added 18 inventory regression tests covering origin routing, full inventories, rollback, component
  matching, carried-box movement, box identity and item conservation.

## 1.1.18

### Added

- Added a `Sort: Name` / `Sort: Count` toggle to the TakeItOut item list screen.
- Applied the selected item sorting mode to both `All Items` and linked-container contents popups.
- Persisted the selected item sorting mode in the client settings.
- Added Litematica material list integration for linked world containers:
  - linked container contents are requested when the material list opens or refreshes;
  - returned container item counts are added to Litematica's available material counts;
  - the material list refreshes after the server response arrives.
- Added cross-dimension linked container support within the same world/server.
- Added a server config file, `takeitout-server.json`, for controlling linked-container exchange:
  - `linked_container_exchange_mode`: `disabled`, `same_dimension`, or `cross_dimension`;
  - `linked_container_scan_limit`: maximum linked containers scanned per server request;
  - `allowed_exchange_dimensions`: optional dimension allowlist for linked-container exchange.
  - Example:
    ```json
    {
      "linked_container_exchange_mode": "cross_dimension",
      "linked_container_scan_limit": 64,
      "allowed_exchange_dimensions": [
        "minecraft:overworld",
        "minecraft:the_nether"
      ]
    }
    ```
    An empty `allowed_exchange_dimensions` list allows all dimensions for the selected mode.

### Changed

- `Count` sorting now shows the most abundant items first and falls back to item name for stable ordering.
- `Name` sorting is case-insensitive and falls back to count for stable ordering.
- Linked container network requests now include the source dimension instead of only block coordinates.

### Fixed

- Improved compatibility with Carpet/debug renderers so linked container outlines are less likely to be hidden or overwritten.
- Fixed overlap between the TakeItOut screen toolbar buttons and the world/container status text.

### Notes

- Litematica already counts items stored in shulker boxes in the player's inventory; TakeItOut only adds linked world-container counts on top.
- Linked container material counts and extraction use the configured server-side scan limit per request.
- Linked container outlines are still rendered only in the player's current dimension.
