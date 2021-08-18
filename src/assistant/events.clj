(ns assistant.events
  (:require [discljord.formatting :refer [user-tag]]))

(defmulti handler
  "Handler for Discord API events."
  (fn [type _] type))

(defmethod handler :ready
  [_ data]
  (println (str "Connected as " (user-tag (:user data)) " (" (-> data :user :id) ").")))
