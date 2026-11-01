/** MMKV key of the app log that Settings → Logs shows, copies and exports. */
export const LOGS_STORAGE_KEY = "logs";

// The native player mirrors its log in here too (useNativePlayerLogBridge),
// so one playback can add a couple of dozen lines; 100 was pushing the
// startup/audio-route entries out before a user got to Settings → Logs.
export const MAX_LOG_ENTRIES = 250;

// The entry count alone doesn't bound the log: one error with a response body
// in its data can be megabytes, and the whole log is parsed and rewritten on
// every entry written.
export const MAX_LOG_ENTRY_CHARS = 8 * 1024;
