(ns frontend.mobile.calendar
  (:require [cljs-time.core :as t]
            [frontend.handler.route :as route-handler]
            [frontend.mobile.calendar-state :as calendar-state]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [rum.core :as rum]))

(def selected-date->journal-page-name calendar-state/selected-date->journal-page-name)
(def calendar-open? calendar-state/calendar-open?)
(def open-calendar! calendar-state/open-calendar!)
(def close-calendar! calendar-state/close-calendar!)

(defn go-to-selected-date!
  [selected-date]
  (let [page-name (calendar-state/selected-date->journal-page-name selected-date)]
    (calendar-state/set-selected-date! selected-date)
    (calendar-state/close-calendar!)
    (when-not (nil? page-name)
      (when-not (nil? (state/get-current-repo))
        (route-handler/redirect-to-page! page-name {:push true})))))

(rum/defc open-calendar-modal < rum/reactive
  []
  (when (rum/react (calendar-state/calendar-open-state))
    [:div.journal-open-calendar-backdrop
     {:style {:position "fixed"
              :inset 0
              :z-index 9999
              :display "flex"
              :align-items "flex-end"
              :justify-content "center"
              :background "rgba(0,0,0,0.35)"
              :padding "16px 12px 88px"}
      :on-click (fn [e]
                  (util/stop e)
                  (calendar-state/close-calendar!))}
     [:div.journal-open-calendar-card
      {:style {:width "min(360px, 100%)"
               :border-radius "18px"
               :background "var(--ls-primary-background-color)"
               :box-shadow "0 16px 48px rgba(0,0,0,0.35)"
               :padding "14px"
               :border "1px solid var(--ls-border-color)"}
       :on-click util/stop
       :on-mouse-down (fn [e] (.stopPropagation e))}
      [:div.flex.flex-row.items-center.justify-between.mb-2
       [:div.font-medium "Open calendar"]
       [:button.button.icon
        {:aria-label "Close calendar"
         :on-click (fn [e]
                     (util/stop e)
                     (calendar-state/close-calendar!))}
        (ui/icon "x" {:size 20})]]
      (ui/datepicker
       (or (rum/react (calendar-state/selected-date-state)) (t/today))
       {:class "journal-open-calendar"
        :show-today? true
        :on-change (fn [e selected-date]
                     (util/stop e)
                     (go-to-selected-date! selected-date))})]]))
