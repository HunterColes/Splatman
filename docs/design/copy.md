# Copy

## Voice

Plain, calm, short. Second person, present tense, sentence case. Say what to do, not what went wrong inside the
app. No exclamation marks, no "Oops", no jargon on screens a first-time user sees ("splat" is fine, it is the
product; "keyframe", "SH degree", "densification" are not). Numbers in digits. One instruction at a time.
"Splat" for the thing, "scan" for the act and the library item.

Strings live in each feature's `res/values/strings.xml` under the keys below.

## Onboarding

| Key | Text |
|---|---|
| `onb_welcome_title` | Scan anything in 3D |
| `onb_welcome_body` | Walk around something with your phone. Splatman turns it into a 3D splat you can spin, zoom and share. |
| `onb_welcome_private` | Everything stays on your phone. No account, no uploads. |
| `onb_tips_title` | For a good scan |
| `onb_tip_slow` | Move slowly, like you are carrying a full cup |
| `onb_tip_around` | Go all the way around, then again higher and lower |
| `onb_tip_light` | Use even light. Avoid shiny, see-through or plain white things |
| `onb_camera_title` | Splatman needs your camera |
| `onb_camera_body` | The camera sees the object and tracks how your phone moves. Video never leaves your phone. |
| `onb_camera_allow` | Allow camera |
| `onb_skip` | Skip |
| `onb_next` | Next |
| `onb_start` | Start scanning |
| `ar_unsupported_title` | This phone can't track motion |
| `ar_unsupported_body` | Scanning needs ARCore, which this phone doesn't support. You can still open and view splat files. |
| `ar_unsupported_title` (foss) | This phone has no camera Splatman can scan with |
| `ar_unsupported_body` (foss) | Scanning needs a back camera. You can still open, view and export splat files. |
| `ar_install_title` | ARCore is not installed (needs Google Play) |
| `ar_install_body` | Scanning needs Google Play Services for AR. It's free and works offline once installed. |
| `ar_install_action` | Install |
| `ar_check_failed_title` | Couldn't check ARCore |
| `ar_check_failed_body` | The phone didn't answer whether it can run ARCore, which scanning needs. Try again; you can still open and view splat files. |
| `ar_details` | Details: %1$s (the raw reason: ARCore's answer such as `SUPPORTED_NOT_INSTALLED`, or the exception's class and message; also logged) |

## Library

| Key | Text |
|---|---|
| `lib_title` | Splatman |
| `lib_scan` | Scan |
| `lib_import` | Open a file |
| `lib_empty_title` | No scans yet |
| `lib_empty_body` | Walk around something you like and it'll show up here as a 3D splat. |
| `lib_empty_sample` | Try a sample |
| `lib_processing` | Building… %1$d%% |
| `lib_failed` | Couldn't build |
| `lib_selected` | %1$d selected |
| `lib_delete_confirm_title` | Delete %1$d scans? |
| `lib_delete_confirm_body` | They'll be removed from this phone. Files you already shared or saved are not affected. |
| `lib_deleted` | Deleted %1$d |
| `lib_undo` | Undo |
| `scan_default_name` | Scan %1$s (the local date and time, e.g. "Scan 1 Nov, 21:40") |

## Library: search, sort and folders

[screens/library.md](screens/library.md#search-sort-and-folders), ticket #301. Rows marked *plural* are Android
`<plurals>` (one / other); the text shows both. Every `%s` that holds a user's name (scan, folder or query) is
bidi-wrapped (`BidiFormatter.unicodeWrap`) so emoji and right-to-left names do not reorder the sentence around them.
Existing keys reused: `lib_name`, `lib_cancel`, `lib_rename`, `lib_undo`, `lib_card_detail` (count · size on a folder
cell).

| Key | Text | When |
|---|---|---|
| `lib_search` | Search scans | ⌕ top bar button, content description |
| `lib_search_hint` | Search scans | search field placeholder and label, at the root |
| `lib_search_hint_folder` | Search in %1$s | search field placeholder and label, inside a folder |
| `lib_search_clear` | Clear search | × button, content description |
| `lib_search_close` | Close search | ← button, content description |
| `lib_search_results` | *plural* %1$d scan found / %1$d scans found | TalkBack, when the count changes |
| `lib_search_none` | No scans found | TalkBack, nothing matches |
| `lib_search_empty_title` | Nothing called “%1$s” | no match, the query |
| `lib_search_empty_body` | Check the spelling, or try part of the name. | no match |
| `lib_search_everywhere` | Search all of Library | no match inside a folder, text button |
| `lib_search_in_folder` | In %1$s | root results: a scan inside a folder (card line and TalkBack) |
| `lib_sort_description` | Sort: %1$s | sort chip, TalkBack (%1$s = one of the four below) |
| `lib_sort_newest` | Newest first | chip label and menu item (default) |
| `lib_sort_oldest` | Oldest first | |
| `lib_sort_name` | Name, A to Z | |
| `lib_sort_size` | Largest first | |
| `lib_folder_section` | Folders | root section label, when the root has folders and scans |
| `lib_folder_section_scans` | Scans | root section label, same condition |
| `lib_folder_new` | New folder | root overflow item, Move sheet row, dialog title |
| `lib_folder_create` | Create | new folder dialog button |
| `lib_folder_rename` | Rename folder | folder menu item, rename dialog title |
| `lib_folder_delete` | Delete folder | folder menu item, confirm button (error colour) |
| `lib_folder_more` | Options for %1$s | ⋮ on a folder cell, content description |
| `lib_folder_name_taken` | You already have a folder called %1$s | name dialog, field error |
| `lib_folder_name_count` | %1$d/60 | name dialog counter, from 50 characters |
| `lib_folder_count` | *plural* %1$d scan / %1$d scans | folder cell, Move sheet row |
| `lib_folder_count_empty` | Empty | folder cell and Move row with no scans |
| `lib_folder_description` | Folder, %1$s, %2$s, %3$s | folder cell, TalkBack: name, count, size ("Folder, Desk stuff, 3 scans, 96 megabytes"); an empty folder drops the size |
| `lib_folder_empty_title` | This folder is empty | inside an empty folder |
| `lib_folder_empty_body` | Select scans in Library and tap Move to put them here, or start a scan from here. | inside an empty folder |
| `lib_folder_delete_title` | Delete the folder %1$s? | confirm dialog |
| `lib_folder_delete_body` | *plural* Its scan moves back to Library. Nothing is deleted from your phone. / Its %1$d scans move back to Library. Nothing is deleted from your phone. | confirm dialog |
| `lib_folder_delete_body_empty` | It's empty, so nothing else changes. | confirm dialog, empty folder |
| `lib_folder_deleted` | Folder deleted | snackbar, with `lib_undo` |
| `lib_folder_error` | Couldn't save the folder. Try again. | snackbar: create, rename or delete failed |
| `lib_move` | Move | selection top bar action, content description |
| `lib_move_title` | *plural* Move %1$d scan to / Move %1$d scans to | Move sheet title |
| `lib_move_root` | Library | Move sheet row: back to the top level |
| `lib_move_here_now` | Here now | Move sheet row: where every selected scan already is |
| `lib_moved` | *plural* Moved %1$d scan to %2$s / Moved %1$d scans to %2$s | snackbar, with `lib_undo`; %2$s is a folder name or `lib_move_root` |
| `lib_move_error` | Couldn't move the scans. Try again. | snackbar |

## Capture

| Key | Text | When |
|---|---|---|
| `cap_permission_title` | Camera access is off | denied once |
| `cap_permission_body` | Splatman needs the camera to scan. | |
| `cap_permission_allow` | Allow camera | |
| `cap_permission_settings` | Open settings | denied permanently |
| `cap_starting` | Starting camera… | |
| `cap_find_surface` | Move your phone slowly to look around | tracking not ready |
| `cap_aim` | Point at what you want to scan, then tap it | tracking ready, no subject |
| `cap_aim_hint` | Or tap Start to scan what's in the middle | |
| `cap_start` | Start | |
| `cap_walk` | Walk slowly around it | capturing, nothing wrong |
| `cap_go_higher` | Now go around again, a little higher | low band done |
| `cap_go_lower` | Now go around once more, from lower down | mid and high done |
| `cap_warn_fast` | Slow down | motion blur or fast rotation |
| `cap_warn_dark` | Too dark. Find more light | |
| `cap_warn_close` | Step back a little | |
| `cap_warn_far` | Move a little closer | |
| `cap_warn_lost` | Lost track. Point back at the object | tracking lost |
| `cap_paused` | Paused | |
| `cap_coverage` | %1$d%% covered | ring caption |
| `cap_ready` | Ready. Keep going for more detail, or finish | threshold passed |
| `cap_finish` | Finish | |
| `cap_discard_title` | Discard this scan? | close while capturing |
| `cap_discard_body` | You'll lose what you've captured so far. | |
| `cap_discard_confirm` | Discard | |
| `cap_keep_scanning` | Keep scanning | |
| `cap_close` | Close | Close button description |
| `cap_help` | Scanning tips | Help button description |
| `cap_status_starting` | Starting | status pill |
| `cap_status_tracking` | Tracking | status pill |
| `cap_status_limited` | Tracking: limited | status pill |
| `cap_status_lost` | Lost track | status pill |
| `cap_status_ready` | Ready | status pill, pose-free, camera open, not recording |
| `cap_status_recording` | Recording | status pill, pose-free, recording |
| `cap_pf_aim` | Point at what you want to scan, then tap Start | pose-free Aim (no subject tap) |
| `cap_pf_aim_hint` | Camera positions are worked out after the scan, not during it | pose-free Aim, second line |
| `cap_start_capture` | Start capture | capture button description |
| `cap_pause_capture` | Pause capture | capture button description |
| `cap_resume_capture` | Resume capture | capture button description |
| `cap_caption` | %1$s · %2$d frames | elapsed time and frames kept |
| `cap_ring_complete` | %1$d percent covered | ring description, nothing missing |
| `cap_ring_missing` | %1$d percent covered. Missing: %2$s, %3$s | ring description: band, side |
| `cap_band_low` / `_level` / `_high` | low / level / high | |
| `cap_side_near` / `_left` / `_right` / `_far` / `_all` | this side / left side / right side / far side / all the way round | |
| `cap_ring_toggle` | Show a larger ring | ring tap action |

## Processing

| Key | Text |
|---|---|
| `proc_title` | Building your splat |
| `proc_stage_prepare` | Preparing frames |
| `proc_stage_build` | Building the splat |
| `proc_stage_refine` | Refining detail |
| `proc_stage_save` | Saving |
| `proc_eta` | About %1$s left |
| `proc_eta_unknown` | Working out how long this takes… |
| `proc_leave` | You can leave this screen. We'll let you know when it's ready. |
| `proc_hot` | Your phone is warm, so this is going slower to cool down. |
| `proc_cancel` | Cancel |
| `proc_cancel_title` | Stop building? |
| `proc_cancel_body` | Your captured frames are kept, so you can build it later. |
| `proc_cancel_confirm` | Stop |
| `proc_done` | Your splat is ready |
| `proc_open` | View it |
| `proc_failed_title` | Couldn't build this scan |
| `proc_failed_tracking` | The phone lost track too often. Try again with slower movement and more light. |
| `proc_failed_frames` | Not enough sharp frames. Try again with slower movement. |
| `proc_failed_memory` | Your phone ran out of memory. Close other apps and try again. |
| `proc_failed_storage` | Not enough free space. Free up %1$s and try again. |
| `proc_failed_unknown` | Something went wrong while building. Your frames are kept. |
| `proc_retry` | Try again |
| `proc_delete` | Delete scan |
| `proc_needs_poses_title` | Camera positions not built yet |
| `proc_needs_poses_frames` | All %1$d frames are saved, so you can build this splat later. (one: Your %1$d frame is saved, so you can build this splat later.) |
| `proc_back_library` | Back to library |
| `notif_channel` | Building splats |
| `notif_running` | Building %1$s · %2$d%% |
| `notif_done` | %1$s is ready to view |
| `notif_failed` | Couldn't build %1$s |

## Viewer

| Key | Text |
|---|---|
| `view_loading` | Opening %1$s… |
| `view_hint` | Drag to spin · Two fingers to move · Pinch to zoom |
| `view_hint_ok` | Got it |
| `view_hint_room_look` | Drag to look around |
| `view_hint_room_move` | Two fingers to slide |
| `view_hint_room_walk` | Pinch to step in or back |
| `view_reset` | Reset view |
| `view_autorotate` | Auto-rotate |
| `view_share` | Share |
| `view_info` | Details |
| `view_rename` | Rename |
| `view_delete` | Delete |
| `view_delete_title` | Delete this scan? |
| `view_info_splats` | %1$s splats |
| `view_info_size` | %1$s |
| `view_info_frames` | Built from %1$d frames in %2$s |
| `view_info_imported` | Imported from %1$s |
| `view_info_room` | Room scan: opens from inside |
| `view_a11y_render_room` | 3D view inside %1$s, %2$s splats |
| `view_a11y_turn_left` / `_turn_right` | Turn left / Turn right |
| `view_a11y_look_up` / `_look_down` | Look up / Look down |
| `view_a11y_step_in` / `_step_back` | Step in / Step back |
| `view_error_title` | Can't open this splat |
| `view_error_body` | The file may be damaged or in a format Splatman doesn't read. |
| `view_error_memory` | This splat is too big for this phone's memory. |

## Crop

Viewer crop mode ([screens/crop.md](screens/crop.md)). Counts use the localized number formatter.

| Key | Text | When |
|---|---|---|
| `crop_action` | Crop | Viewer bottom bar icon, content description |
| `crop_title` | Crop | crop mode top bar |
| `crop_hint` | Drag a dot to move a side. The dim part gets cut. | editing |
| `crop_size` | %1$s × %2$s × %3$s | size pill: wide × deep × tall, each `42 cm` or `1.20 m` |
| `crop_size_desc` | Box %1$s wide, %2$s deep, %3$s tall | size pill, TalkBack |
| `crop_face_picker` | Side to move | chip group, TalkBack |
| `crop_face_top` | Top | chip |
| `crop_face_bottom` | Bottom | chip |
| `crop_face_front` | Front | chip |
| `crop_face_back` | Back | chip |
| `crop_face_left` | Left | chip |
| `crop_face_right` | Right | chip |
| `crop_face_top_desc` | Top face | handle, TalkBack |
| `crop_face_bottom_desc` | Bottom face | handle, TalkBack |
| `crop_face_front_desc` | Front face | handle, TalkBack |
| `crop_face_back_desc` | Back face | handle, TalkBack |
| `crop_face_left_desc` | Left face | handle, TalkBack |
| `crop_face_right_desc` | Right face | handle, TalkBack |
| `crop_in` | In | nudge button; handle custom action |
| `crop_out` | Out | nudge button; handle custom action |
| `crop_in_desc` | Move %1$s in 1 cm | TalkBack, %1$s = a `crop_face_*_desc` |
| `crop_out_desc` | Move %1$s out 1 cm | TalkBack |
| `crop_limit` | This side can't move further | announced at a limit |
| `crop_reset` | Reset box | |
| `crop_cancel` | Cancel | button; Close (×) content description |
| `crop_apply` | Apply | |
| `crop_render_desc` | 3D view of %1$s with a crop box | render, TalkBack |
| `crop_applying` | Cropping… | applying |
| `crop_empty` | Nothing is inside the box. Make it bigger and try again. | refused: empty result |
| `crop_nothing` | Everything is inside the box, so nothing was cut. | snackbar |
| `crop_error` | Couldn't save the crop. Check free space and try again. | save error |
| `crop_done` | Cropped. %1$s splats left | snackbar after Apply |
| `crop_revert` | Revert edits | overflow item, only when an original is kept; covers crop and erase ([erase.md](screens/erase.md#revert-edits-one-label-for-crop-and-erase)) |
| `crop_revert_title` | Go back to the original? | confirm dialog |
| `crop_revert_body` | This undoes every crop and erase on this scan, not just the last one. | confirm dialog |
| `crop_revert_confirm` | Revert | confirm dialog button (beside `crop_cancel`) |
| `crop_reverting` | Restoring the original… | reverting |
| `crop_reverted` | Original restored | snackbar |
| `crop_revert_error` | Couldn't restore the original. Try again. | snackbar |

## Erase

Viewer erase mode ([screens/erase.md](screens/erase.md)). Counts use the localized number formatter. Revert and its
dialog use the `crop_revert*` keys (one Revert for crop and erase).

| Key | Text | When |
|---|---|---|
| `erase_action` | Erase | Viewer bottom bar icon, content description |
| `erase_title` | Erase | erase mode top bar |
| `erase_close_desc` | Close erase | Close (×), TalkBack |
| `erase_hint` | Paint over what to remove. Two fingers to turn. | nothing marked |
| `erase_hint_marked` | Pink splats get removed. Undo or keep painting. | marked |
| `erase_count` | 1 splat marked / %1$s splats marked | count pill; plurals (one, other) |
| `erase_size_picker` | Brush size | segment group, TalkBack |
| `erase_size_small` | Small | segment |
| `erase_size_medium` | Medium | segment |
| `erase_size_large` | Large | segment |
| `erase_isolated` | Remove isolated splats | tonal button; marks only |
| `erase_isolated_running` | Finding isolated splats… | button while the pass runs |
| `erase_isolated_none` | No isolated splats found | snackbar |
| `erase_undo` | Undo | text button |
| `erase_undo_desc` | Undo last stroke | Undo, TalkBack |
| `erase_cancel` | Cancel | button |
| `erase_apply` | Apply | button; disabled while nothing is marked |
| `erase_render_desc` | 3D view of %1$s, %2$s splats marked | render, TalkBack |
| `erase_applying` | Removing splats… | applying |
| `erase_all` | Every splat is marked. Undo some and try again. | refused: all marked |
| `erase_error` | Couldn't save. Check free space and try again. | refused: store error |
| `erase_done` | Removed %1$s splats. %2$s left | snackbar after Apply |
| `erase_discard_title` | Discard your marks? | Back or Close with marks |
| `erase_discard_body` | Nothing has been removed yet. | discard dialog |
| `erase_keep` | Keep painting | discard dialog, dismiss |
| `erase_discard` | Discard | discard dialog, confirm (`error`) |

## Export

| Key | Text |
|---|---|
| `exp_title` | Share or save |
| `exp_ply` | PLY (standard 3DGS) |
| `exp_ply_body` | Opens in most splat tools: SuperSplat, Blender add-ons, Postshot, web viewers |
| `exp_splat` | .splat (compact) |
| `exp_splat_body` | Smaller, loads fast in web viewers. Drops some colour detail |
| `exp_size` | About %1$s |
| `exp_share` | Share |
| `exp_save` | Save to device |
| `exp_writing` | Preparing file… |
| `exp_saved` | Saved %1$s |
| `exp_error` | Couldn't write the file. Check free space and try again. |
| `exp_low_storage` | Needs %1$s free. You have %2$s. |
| `exp_video` | Video (MP4) |
| `exp_video_body` | A 6-second spin of the scan. Plays in any chat app, no splat viewer needed |
| `exp_video_writing` | Making the video… |
| `exp_video_keep_open` | Keep Splatman open until it's done. |
| `exp_video_cancel` | Cancel |
| `exp_video_stopped` | The video stopped when you left Splatman. Try again. |
| `exp_video_error` | Couldn't make a video on this phone. PLY and .splat still work. |

## Import

| Key | Text |
|---|---|
| `imp_validating` | Reading %1$s… |
| `imp_point_cloud` | This PLY is a point cloud, not a Gaussian splat. Splatman opens splat files only. |
| `imp_unsupported` | Splatman opens .ply and .splat splat files. This one is %1$s. |
| `imp_corrupt` | This file looks damaged and can't be opened. |
| `imp_too_large` | This splat has %1$s splats, too many for this phone. |
| `imp_done` | Opened %1$s |

## Settings and About

| Key | Text |
|---|---|
| `set_title` | Settings |
| `set_theme` | Theme |
| `set_theme_system` | Match system |
| `set_theme_dark` | Dark |
| `set_theme_light` | Light |
| `set_export_format` | Default export format |
| `set_keep_frames` | Keep capture frames |
| `set_keep_frames_body` | Lets you rebuild a scan later. Uses more space. |
| `set_haptics` | Vibration feedback |
| `set_storage` | Storage |
| `set_storage_body` (plural) | one: %1$s used by %2$d scan; other: %1$s used by %2$d scans |
| `set_storage_empty` | No scans yet |
| `set_clear_frames` | Clear capture frames |
| `set_clear_frames_body` | Frees %1$s. Scans stay; you just can't rebuild them. |
| `set_tips` | Show scanning tips again |
| `set_about` | About |
| `about_tagline` | Free, offline 3D splat scanner. |
| `about_version` | Version %1$s |
| `about_source` | Source code |
| `about_licences` | Open-source licences |
| `about_samples` | Sample scans |
| `about_privacy` | Splatman has no internet permission. Nothing you scan leaves your phone unless you share it. |
| `about_open_link` | Open in browser |
| `about_no_browser` | No app on this phone can open links |
| `about_licences_missing` | The licence list is missing from this build. |
