# Anotepad

Anotepad is a simple Android note app that works with real text files in a folder you choose on your device. Notes stay as plain `.txt` files, so they are easy to open, copy, back up, and edit outside the app.

**[Get it on Google Play](https://play.google.com/store/apps/details?id=com.anotepad)**

## What the app is for

Anotepad is built for people who want fast plain-text notes without a closed database format.

You choose one folder on your phone, and the app works directly inside it:
- create notes
- create subfolders
- browse files and folders
- search through notes
- sync with Google Drive if you want

The app works locally even without an account or cloud sync.

## What you can do in the app

### Organize notes in folders
- Pick your main folder once and change it later if needed.
- Create new notes in the current folder.
- Create subfolders and move deeper into them.
- Go back to parent folders.
- Rename files and folders.
- Delete files and folders.
- Copy notes to another folder.
- Move notes to another folder.

### Work with plain text files
- Notes are stored as `.txt` files.
- Only text files are shown in the app.
- Files stay readable outside Anotepad in any editor or file manager.

### Browse notes in two ways
- `List view` for a compact file list.
- `Feed view` for reading note previews one after another.
- Refresh the current folder at any time.
- Change the note list font size.
- Sort files from `A to Z` or from `Z to A`.

### Open and edit notes quickly
- Create a note from the top toolbar or the floating action button.
- Auto-save while editing.
- Save manually with the save button.
- Save when the app goes to the background.
- Undo and redo changes.
- Show links inside notes:
  - web links
  - email addresses
  - phone numbers
- Open notes in read mode if you do not want the keyboard to appear immediately.
- Change the editor font size.

### Let the first line become the file name
- New notes get a file name automatically.
- The app can keep the file name synced with the first line of the note.
- Shared notes use a timestamp-based name automatically.

### Use date and time templates
- Insert the current date or time into a note with one tap.
- Manage your own date/time templates.
- Use built-in examples such as full date and time or short date formats.
- Automatically insert a chosen template when creating a new note.

### Search inside notes
- Search through notes in the current folder and its subfolders.
- Use normal text search.
- Use regex search when needed.
- Open a note directly from the search results.
- See a short text snippet for each match.

### Share text into Anotepad
- Send text from another app to Anotepad through Android Share.
- The app creates a note in the `Shared` folder automatically.
- If the `Shared` folder does not exist yet, Anotepad creates it for you.
- Shared notes start with a timestamp-based title, then you can edit them like any other note.

### Share notes out of the app
- Share a note with other apps.
- Share folders through the Android share menu when supported by the device.

### Get help inside the app
- On first use, the app shows short toolbar tips so the main actions are easier to discover.
- If folder access is lost, the app asks you to choose the folder again.

## Google Drive sync

Google Drive sync is optional.

If you enable it, you can:
- sign in with your Google account
- let the app find or create its Drive folder
- connect to an existing Drive folder
- disconnect the current Drive folder
- run sync manually
- enable auto sync on app start
- pause sync temporarily
- ignore deletions that come from Drive

Drive sync is mainly meant for keeping your Anotepad notes backed up and available across devices that use Anotepad.

## Settings overview

### Storage
- Main folder
- Change folder

### File browser
- File list font size
- File sorting: `A to Z` or `Z to A`

### Editor
- Auto-save
- Editor font size
- Autolink web
- Autolink email
- Autolink phone
- Open notes in read mode
- Sync title with file name

### New note
- Auto-insert template
- Template text or date/time pattern for new notes

### Date/time templates
- Open template manager
- Add templates
- Edit templates
- Delete templates

### Sync
- Enable Google Drive sync
- Sign in / sign out
- Connect or disconnect Drive folder
- Sync now
- Pause sync
- Auto sync on start
- Ignore deletions from Drive

## Recent updates

Recent git changes reflected in the app:
- Shared note names are now cleaner and no longer include milliseconds.
- The browser now refreshes automatically after the first shared note creates the `Shared` folder.

## Privacy and permissions

Anotepad is local-first. Network access is only needed for Google Drive sync and Google sign-in.

Useful links:
- [Privacy Policy](https://anotepad.tirmudam.org/PRIVACY_POLICY)
- [Account deletion](ACCOUNT_DELETION.md)

## Limitations

- The app works with plain text notes only.
- Other file types are ignored.
- There is no encryption built into the app.

## Android version

- Android 10 and newer

## License

Licensed under the MIT License. See [LICENSE](LICENSE).

Trademark policy: see [TRADEMARK.md](TRADEMARK.md).
