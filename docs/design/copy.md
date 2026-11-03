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
| `crop_revert` | Revert crop | overflow item, only when an original is kept |
| `crop_revert_title` | Go back to the original? | confirm dialog |
| `crop_revert_body` | This undoes every crop on this scan, not just the last one. | confirm dialog |
| `crop_revert_confirm` | Revert | confirm dialog button (beside `crop_cancel`) |
| `crop_reverting` | Restoring the original… | reverting |
| `crop_reverted` | Original restored | snackbar |
| `crop_revert_error` | Couldn't restore the original. Try again. | snackbar |

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
| `set_storage_body` | %1$s used by %2$d scans |
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
