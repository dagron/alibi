(ns alibi.entry-page-state
  (:require
   [cljs.reader]
   [clojure.data]
   [alibi.activity-graphic-data-source :as ag-ds]
   [om.core :as om]
   [alibi.logging :refer [log]]
   [time.core :refer [str->unix unix->date-str unix->time-str try-parse-time
                      expand-time]]))

(let [view-data-input (js/document.getElementById  "view-data")
      view-data (cljs.reader/read-string
                           (.-value view-data-input))]
  (def task-name (get-in view-data [:projects-tasks :tasks-by-id]))
  (def project-name (get-in view-data [:projects-tasks :projects-by-id]))

  (ag-ds/load! (:activity-graphic view-data))

  (def initial-state
    (let [options (get-in (:initial-state view-data)
                          [:post-new-entry-bar :options])
          options-by-id (into {} (for [{:keys [value] :as opt} options]
                                   [value opt]))]

      (assoc-in (:initial-state view-data)
                [:post-new-entry-bar :options-by-id] options-by-id))))


(def form-selected-date #(get-in % [:selected-date :date]))
(def form-selected-task #(get-in % [:selected-task]))
(def form-selected-task-id #(get-in % [:selected-task :task-id]))
(def form-selected-project-id #(get-in % [:selected-task :project-id]))
(def form-start-time #(get-in % [:post-entry-form :startTime]))
(def form-end-time #(get-in % [:post-entry-form :endTime]))
(def form-entry-id #(get-in % [:post-entry-form :entry-id]))
(def form-comment #(get-in % [:post-entry-form :comment]))
(def form-billable? #(get-in % [:post-entry-form :isBillable]))
(def form-submitted? #(get-in % [:submitted?]))
(def form-form-at-submit-time #(get-in % [:form-at-submit-time]))

(defn form-data-entry->form [entry]
  (when entry
    {:selected-task {:task-id (:task-id entry)
                     :project-id (:project-id entry)}
     :selected-date {:date (unix->date-str (:from entry))}
     :post-entry-form {:isBillable (:billable? entry)
                       :comment (:comment entry)
                       :startTime (unix->time-str (:from entry))
                       :endTime (unix->time-str (:till entry))
                       :entry-id (:entry-id entry)}}))

(defn form->data-entry [form]
  (let [from (str->unix (form-selected-date form) (form-start-time form))
        till (str->unix (form-selected-date form) (form-end-time form))
        duration (- till from)]
    {:task-id (form-selected-task-id form)
     :project-id (form-selected-project-id form)
     :billable? (form-billable? form) :comment (form-comment form)
     :user-id 0 :from from :till till :duration duration
     :task (task-name (form-selected-task-id form))
     :project (project-name (form-selected-project-id form))
     :entry-id (form-entry-id form)}))

(defn form-validate-form [form]
  (let [validate
        (fn [f field-name msg errs]
          (if (f form)
            errs
            (conj errs [field-name msg])))
        errors
        (->> []
             (validate (comp seq form-selected-task) "SelectedItem" "Task not selected")
             (validate (comp try-parse-time form-start-time) "Start time",
                       "Please enter a valid time value (e.g. 13:37)")
             (validate (comp try-parse-time form-end-time) "End time",
                       "Please enter a valid time value (e.g. 13:37)"))
        has-field-error? (into {} errors)]
    (if (or (has-field-error? "Start time") (has-field-error? "End time"))
      errors
      (cond-> errors
        (not (neg? (.compareTo (try-parse-time (form-start-time form))
                               (try-parse-time (form-end-time form)))))
        (conj ["End time" "End time should come after start time"])))))

(defn form-get-editing-entry [form]
  (let [form (-> form
                 (update-in [:post-entry-form :startTime] expand-time)
                 (update-in [:post-entry-form :endTime] expand-time))]
    (when-not (seq (form-validate-form form))
      (form->data-entry form))))

(defn form-get-editing-entry-id [form]
  (:entry-id (form-get-editing-entry form)))

(defn entries-add-form-entry [entries form]
  (if-let [form-entry (form-get-editing-entry form)]
    (conj (vec (remove #(= (:entry-id form-entry) (:entry-id %)) entries))
          form-entry)
    entries))

(defn entries-find-entry [entries entry-id]
  {:pre [(integer? entry-id)]}
  (->> entries
       (filter #(= (:entry-id %) entry-id))
       first))

(defonce state (atom (merge {:activity-graphic-data []
                             :activity-graphic-mouse-over-entry {}}
                            initial-state)))

(def entries :activity-graphic-data)

(defn selected-task-cursor []
  (om/ref-cursor (get-in (om/root-cursor state) [:form :selected-task])))
(defn entry-screen-form-cursor []
  (om/ref-cursor (:form (om/root-cursor state))))

(defn entries-cursor []
  (om/ref-cursor (:activity-graphic-data (om/root-cursor state))))
(defn mouse-over-entry-cursor []
  (om/ref-cursor (:activity-graphic-mouse-over-entry (om/root-cursor state))))
(defn post-new-entry-bar-cursor []
  (om/ref-cursor (get-in (om/root-cursor state) [:post-new-entry-bar])))


(defn parse-float [v] (js/parseFloat v))

(defn form-reducer
  [prev-state {:keys [action] :as payload} next-state]
  (let [empty-state {:startTime ""
                     :endTime ""
                     :isBillable false
                     :comment ""
                     :entry-id "new"}]
    (case action
      :change-comment
      (assoc prev-state :comment (:comment payload))

      :change-start-time
      (assoc prev-state :startTime (:start-time payload))

      :change-end-time
      (assoc prev-state :endTime (:end-time payload))

      :change-billable?
      (assoc prev-state :isBillable (:billable? payload))

      :cancel-entry empty-state


      prev-state)))

(defn reducer
  [prev-state {:keys [action] :as payload}]
  ;(log "reducer %o" payload)
  (let [next-state
        (case action
          :select-task
          (assoc-in prev-state [:form :selected-task] (:task payload))

          ;:change-date
          ;(assoc-in prev-state [:post-entry-form :selectedDate] (:date payload))

          :receive-activity-graphic-data
          (-> prev-state
              (assoc-in [:form :selected-date :date] (:for-date payload))
              (assoc :activity-graphic-data (vec (:data payload))))

          :mouse-over-entry
          (assoc prev-state :activity-graphic-mouse-over-entry (:entry payload))

          :mouse-leave-entry
          (assoc prev-state :activity-graphic-mouse-over-entry {})

          :edit-entry
          (let [form-entry (-> (entries prev-state)
                               (entries-find-entry (:entry-id payload))
                               (form-data-entry->form))]
            (-> prev-state
                (assoc :form form-entry)
                (assoc :activity-graphic-mouse-over-entry {})))

          :cancel-entry
          (-> prev-state
              (update :form assoc
                      :submitted? false
                      :form-at-submit-time nil)
              (assoc-in [:form :selected-task] {}))

          :entry-form-show-errors
          ; insert :form
          (update prev-state :form assoc
                  :submitted? true
                  :form-at-submit-time (:form payload))

          prev-state)]
    (update-in next-state [:form :post-entry-form]
               form-reducer payload next-state)))
