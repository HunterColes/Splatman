# ADR 0013: Library folders in Room (DB v4), sort keys and search

Status: accepted (epic #301, card #308). The build cards of #301 share this one model.

## Context

The Library design ([docs/design/screens/library.md](../../design/screens/library.md), "Search, sort and folders")
adds one level of folders, a sort choice and a name search to the list of saved scans. Several cards build it
(storage, migration, repository, sort, search, screens, navigation). They must agree on one schema, one migration
from DB v3, one key for comparing names and one rule for each sort. Scan files stay where
[ADR 0007](0007-formats.md) puts them, and crops stay where [ADR 0011](0011-crop.md) puts them.

## Decision

1. Folders live only in Room. New table `folders`: `id TEXT PRIMARY KEY NOT NULL` (UUID), `name TEXT NOT NULL`
   (trimmed, 1..60 chars), `nameKey TEXT NOT NULL` with unique index `index_folders_nameKey`, `createdAtMs INTEGER
   NOT NULL`. No parent column: one level only.
2. `nameKey` = `LibraryText.key(name)`: trim, Unicode NFD, drop combining marks (`\p{Mn}`), `lowercase(Locale.ROOT)`.
   The same key drives duplicate folder names and search matching. Emoji and RTL text pass through unchanged: an
   emoji or Hebrew/Arabic name keys to itself once trimmed (no case to fold, no reordering).
3. `scans` gains `folderId TEXT` (null = Library root) with index `index_scans_folderId`. No SQLite foreign key
   (ALTER TABLE cannot add one Room validates); the repository keeps rows consistent inside transactions. The
   existing `scans.folder` column stays the scan's directory name under `scans/`; KDoc names the difference
   (`folder` = directory on disk, `folderId` = Library folder row).
4. Migration `MIGRATION_3_4`, `LibraryDatabase.VERSION = 4`: CREATE TABLE folders, CREATE UNIQUE INDEX
   index_folders_nameKey, ALTER TABLE scans ADD COLUMN folderId TEXT, CREATE INDEX index_scans_folderId. Every
   existing row (made, imported, failed, processing) lands at the root (`folderId` null) with every other column
   unchanged; no folder is created. The destructive fallback stays for v1 only.
5. ADR 0007 layout unchanged: `scans/<scan-id>/` stays flat. Moving a scan or deleting a folder only updates rows
   and never touches files, so a processing scan keeps writing to the same path (it just shows at its new place,
   or at the root after its folder is deleted) and crop (ADR 0011) paths do not change. Accepted cost: if the
   database is lost, `sync()` re-adopts every scan at the root and folders are gone; scans are not.
6. Deleting a folder is one transaction: its scans get `folderId = null`, then the row goes; it returns
   `DeletedFolder(folder, scanIds)`. `restore(DeletedFolder)` reinserts the same id, name and date and moves back
   the ids that still exist. A folder action never deletes a scan, processing or not.
7. Move is one transaction setting `folderId` for the given ids, processing scans included; it returns the previous
   place of each id for Undo (`restorePlaces`). A target folder that no longer exists fails the whole move; nothing
   moves.
8. Landing folder: a `LandingFolder` singleton in library-feature (`StateFlow<String?>`) is set while a folder is
   open and cleared at the root. `ScanRepositoryImpl` gives rows made by `create`, `import` and the `refresh`
   adoption of a new scan that folder when it still exists, else the root. Startup `sync()` adopts at the root.
   capture-feature and the bundle format are untouched.
9. API: new `FolderRepository` in `library/domain/repository/` (`folders(): Flow<List<LibraryFolder>>`,
   `create(name)`, `rename(id, name)`, `delete(id): DeletedFolder`, `restore(DeletedFolder)`,
   `move(scanIds, folderId: String?): Map<String, String?>`, `restorePlaces(Map<String, String?>)`);
   `LibraryFolder(id, name, createdAtMs)`; `Scan.folderId: String? = null`. Blank names throw
   `IllegalArgumentException`, duplicate keys throw `FolderNameTaken`. `ScanRepository` keeps its shape.
10. Sort: `LibrarySort { NEWEST, OLDEST, NAME, SIZE }`. NEWEST/OLDEST by `createdAtMs` (imports: the import date);
    NAME natural order (digit runs compared as numbers, text runs by `Collator` at PRIMARY strength for the default
    locale, so case and accents are ignored and "Scan 2" < "Scan 10"); SIZE by `sizeBytes` descending, scans not
    READY last. Ties: newest first, then id. Folders sort by the same key: their createdAtMs, their name, the sum of
    their scans' sizeBytes. One choice for the whole app in SharedPreferences file `library_prefs`, key `sort`,
    stored by enum name; default and unknown value NEWEST.
11. Search runs in memory on `Dispatchers.Default` over the lists the repositories emit: a name matches when
    `key(query)` is a substring of `key(name)`; a blank query filters nothing. No SQL LIKE or FTS (SQLite cannot
    fold accents; 500 rows is small).
12. Route: `NavigationDestination.LibraryFolder(folderId)`, path `library/folder/{folderId}`; a folder that
    disappears while open sends the screen back to the root.

## Rejected

- Folders as directories (`scans/<folder>/<scan>/`): moving a processing scan would race the runner and move ADR
  0007 and 0011 paths.
- A JSON membership file beside the scans: two sources of truth.
- SQLite foreign keys: see point 3.

## Out of scope

The design's list: nested folders, a scan in two folders, folder covers or colours, search by date or size, match
highlighting, per-folder sort, drag to move, select all, folder export. Also full-text search.
