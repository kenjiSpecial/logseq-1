(ns frontend.journal-mobile.status
  "Pure user-facing labels for Journal sync phases.")

(defn phase-label
  [phase online?]
  (cond
    (not online?) "Offline"
    (= phase :syncing) "Syncing"
    (= phase :conflict) "Conflict"
    (= phase :error) "API error"
    (= phase :idle) "Synced"
    :else "Waiting"))
