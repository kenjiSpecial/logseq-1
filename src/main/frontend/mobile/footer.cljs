(ns frontend.mobile.footer
  (:require [clojure.string :as string]
            [frontend.components.svg :as svg]
            [frontend.date :as date]
            [frontend.handler.editor :as editor-handler]
            [frontend.journal-mobile.config :as journal-config]
            [frontend.journal-mobile.status :as journal-status]
            [frontend.journal-mobile.sync :as journal-sync]
            [frontend.mobile.record :as record]
            [frontend.mobile.util :as mobile-util]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [rum.core :as rum]))

(rum/defc mobile-bar-command [command-handler icon]
  [:button.bottom-action
   {:on-mouse-down (fn [e]
                     (util/stop e)
                     (command-handler))}
   (if (= icon "player-stop")
     svg/circle-stop
     (ui/icon icon {:size 24}))])

(defn seconds->minutes:seconds
  [seconds]
  (let [minutes (quot seconds 60)
        seconds (mod seconds 60)]
    (util/format "%02d:%02d" minutes seconds)))

(def *record-start (atom nil))
(rum/defcs audio-record-cp < rum/reactive
  {:did-mount (fn [state]
                (let [comp (:rum/react-component state)
                      callback #(rum/request-render comp)
                      interval (js/setInterval callback 1000)]
                  (assoc state ::interval interval)))
   :will-mount (fn [state]
                 (js/clearInterval (::interval state))
                 (dissoc state ::interval))}
  [state]
  (if (= (state/sub :editor/record-status) "NONE")
    (mobile-bar-command #(do (record/start-recording)
                             (reset! *record-start (js/Date.now))) "microphone")
    [:div.flex.flex-row.items-center
     (mobile-bar-command #(do (reset! *record-start nil)
                              (state/set-state! :mobile/show-recording-bar? false)
                              (record/stop-recording))
                         "player-stop")
     [:div.timer.ml-2
      {:on-click record/stop-recording}
     (seconds->minutes:seconds (/ (- (js/Date.now) @*record-start) 1000))]]))

(rum/defc journal-sync-status < rum/reactive
  []
  (when (journal-config/enabled?)
    (let [{:keys [phase last-error last-diff conflicts queued-count]} @journal-sync/state
          online? (state/sub :network/online?)
          phase-label (journal-status/phase-label phase online?)
          changed-count (or (:changedCount last-diff) 0)
          remote-only-count (or (:remoteOnlyCount last-diff) 0)
          queue-count (or queued-count 0)]
      [:div.flex.items-center.justify-between.px-3.py-1.text-xs.bg-base-2
       {:role "status"
        :aria-live "polite"}
       [:span
        (str "Journal: " phase-label
             (when (pos? queue-count) (str " · queue " queue-count))
             (when (pos? changed-count) (str " · pull " changed-count))
             (when (pos? remote-only-count) (str " · new " remote-only-count))
             (when (seq conflicts) (str " · conflicts " (count conflicts))))]
       [:span.flex.items-center.gap-2
        (when last-error
          [:span.text-red-500 {:title last-error} "needs attention"])
        (when (or (not online?) (= phase :error) (= phase :conflict))
          [:button.text-xs
           {:on-click #(journal-sync/sync-now!)
            :type "button"}
           "Retry"])]])))

(rum/defc footer < rum/reactive
  []
  (when (and (#{:page :home} (state/sub [:route-match :data :name]))
             (not (state/sub :editor/editing?))
             (state/sub :mobile/show-tabbar?)
             (state/get-current-repo))
    [:div.w-full
     (journal-sync-status)
     [:div.cp__footer.w-full.bottom-0.justify-between
      (audio-record-cp)
      (mobile-bar-command
       #(do (when-not (mobile-util/native-ipad?)
              (state/set-left-sidebar-open! false))
            (state/pub-event! [:go/search]))
       "search")
      (mobile-bar-command state/toggle-document-mode! "notes")
      (mobile-bar-command
       #(let [page (or (state/get-current-page)
                       (string/lower-case (date/journal-name)))]
          (editor-handler/api-insert-new-block!
           ""
           {:page page
            :edit-block? true
            :replace-empty-target? true}))
       "edit")]]))
