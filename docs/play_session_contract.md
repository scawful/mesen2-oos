# Play session contract

Canonical text: yaze `docs/internal/play_session_contract.md`.

Desktop checkpoints, warps, savestate jumps, and in-play notes:
`UI/Utilities/PlaySession.cs` (catalog + `AppendFeedback`), surfaced on the
Oracle debug panel. Feature savestates come from `save_state_library.json`.

Android checkpoints, warps, savestate jumps, and in-play notes:
`PlaySession.java`, listed on the Progress presets tab. Put `.mss` files under
external files `OracleLibrary/` using the project-relative `state_path`. Bug
snapshots append `<captures>/BugReports/in-play/feedback-in-play.json` through
`PlaySession.AppendFeedback` (also used from `MediaCapture`).

The feedback file is `yaze.review_board_feedback.v2` with id `in-play` and
`play_schema` `oos.play_feedback.v1`, so Oracle
`Scripts/Review/collect_feedback.py --dest <review-boards>` collects it.
