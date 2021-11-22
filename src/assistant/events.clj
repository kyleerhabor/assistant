(ns assistant.events
  (:require [clojure.tools.logging :as log]
            [assistant.commands :refer [commands]]
            [discljord.formatting :refer [user-tag]]))

(defmulti handler
  "Handler for Discord API events."
  (fn [_ type _] type))

(defmethod handler :interaction-create
  [conn _ interaction]
  (if (#{2 3} (:type interaction))
    ;; Let's just assume the command exists. Nothing should go wrong, right?
    ((:command ((keyword (:name (:data interaction))) commands)) conn interaction)))

(defmethod handler :ready
  [_ _ data]
  (log/info (str "Connected as " (user-tag (:user data))
                 " (" (-> data :user :id) " | Shard: " (first (:shard data)) \))))

(defmethod handler :default
  [_ _ _])
