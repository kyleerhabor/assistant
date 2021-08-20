(ns assistant.events
  (:require [assistant.commands :as commands]
            [discljord.formatting :refer [user-tag]]))

(defmulti handler
  "Handler for Discord API events."
  (fn [_ type _] type))

(defmethod handler :interaction-create
  [msg-ch _ interaction]
  (case (:type interaction)
    2 (let [args (->> interaction
                      :data
                      :options
                      (filter #(not (contains? % :options)))
                      (map :value))]
        (case (-> interaction :data :name)
          "wikipedia" (apply commands/wikipedia msg-ch interaction args)))
    nil))

(defmethod handler :ready
  [_ _ data]
  (println (str "Connected as " (user-tag (:user data)) " (" (-> data :user :id) \))))

(defmethod handler :default
  [_ _ _])
