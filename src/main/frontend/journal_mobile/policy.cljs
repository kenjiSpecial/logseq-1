(ns frontend.journal-mobile.policy
  "Pure trigger policy for the Journal Android sync scheduler.

  Keeping the trigger contract independent from browser timers makes the
  startup, periodic, and foreground behavior deterministic to verify.")

(def startup-delay-ms 4000)
(def dirty-queue-delay-ms 3000)
(def periodic-sync-interval-ms 60000)

(defn trigger-config
  "Returns the externally visible trigger contract for TRIGGER.

  The scheduler uses these values to install timers/listeners; callers can
  inspect the same contract without waiting for real time to elapse."
  [trigger]
  (case trigger
    :startup {:reason :startup
              :delay-ms startup-delay-ms}
    :dirty-queue {:reason :dirty-queue
                  :delay-ms dirty-queue-delay-ms}
    :periodic {:reason :interval
               :interval-ms periodic-sync-interval-ms}
    :foreground {:reason :visibility
                 :visibility "visible"}
    nil))

(defn foreground-visible?
  "Whether a visibilitychange event should trigger a foreground sync."
  [visibility-state]
  (not= "hidden" visibility-state))
