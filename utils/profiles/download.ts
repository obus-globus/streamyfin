/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
import type {
  DeviceProfile,
  SubtitleProfile,
} from "@jellyfin/sdk/lib/generated-client/models";
import { type AudioTranscodeModeType, generateDeviceProfile } from "./native";

/**
 * Download-specific subtitle profiles. Text subtitles are embedded as a soft track so
 * they stay toggleable; image-based formats (PGS/VOBSUB/DVD) can't be embedded and are
 * burned in.
 */
const downloadSubtitleProfiles: SubtitleProfile[] = [
  // Official formats
  { Format: "vtt", Method: "Embed" },
  { Format: "webvtt", Method: "Embed" },
  { Format: "srt", Method: "Embed" },
  { Format: "subrip", Method: "Embed" },
  { Format: "ttml", Method: "Embed" },
  { Format: "dvdsub", Method: "Encode" },
  { Format: "ass", Method: "Embed" },
  { Format: "idx", Method: "Encode" },
  { Format: "pgs", Method: "Encode" },
  { Format: "pgssub", Method: "Encode" },
  { Format: "ssa", Method: "Embed" },
  // Other formats
  { Format: "microdvd", Method: "Embed" },
  { Format: "mov_text", Method: "Embed" },
  { Format: "mpl2", Method: "Embed" },
  { Format: "pjs", Method: "Embed" },
  { Format: "realtext", Method: "Embed" },
  { Format: "scc", Method: "Embed" },
  { Format: "smi", Method: "Embed" },
  { Format: "stl", Method: "Embed" },
  { Format: "sub", Method: "Embed" },
  { Format: "subviewer", Method: "Embed" },
  { Format: "teletext", Method: "Embed" },
  { Format: "text", Method: "Embed" },
  { Format: "vplayer", Method: "Embed" },
  { Format: "xsub", Method: "Encode" },
];

/**
 * Generates a device profile optimized for downloads.
 * Uses the same audio codec logic as streaming but with download-specific bitrate limits.
 */
export const generateDownloadProfile = (
  audioMode: AudioTranscodeModeType = "auto",
): DeviceProfile => {
  // Get the base profile with proper audio codec configuration
  const baseProfile = generateDeviceProfile({ audioMode });

  // Override with download-specific settings
  return {
    ...baseProfile,
    Name: "1. MPV Download",
    // Limit bitrate for downloads (20 Mbps)
    MaxStaticBitrate: 20_000_000,
    MaxStreamingBitrate: 20_000_000,
    // Use download-specific subtitle profiles
    SubtitleProfiles: downloadSubtitleProfiles,
    // Update transcoding profiles with download-specific settings. Transcode to a single
    // MKV over HTTP (not HLS) so embedded subtitle tracks are carried into the download.
    TranscodingProfiles: baseProfile.TranscodingProfiles.map((profile) => {
      if (profile.Type === "Video") {
        return {
          ...profile,
          Protocol: "http",
          Container: "mkv",
          CopyTimestamps: false,
          EnableSubtitlesInManifest: true,
        };
      }
      return profile;
    }),
  };
};

// Default export for backward compatibility
export default generateDownloadProfile();
