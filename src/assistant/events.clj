(ns assistant.events
  (:require [assistant.settings :refer [config]]
            [discljord.formatting :refer [user-tag]]
            [discljord.messaging :refer [bulk-overwrite-global-application-commands!]]))

(defmulti handler
  "Handler for Discord API events."
  (fn [_ type _] type))

(defmethod handler :interaction-create
  [msg-ch _ interaction])

(defmethod handler :ready
  [msg-ch _ data]
  (let [id (-> data :user :id)]
    (println (str "Connected as " (user-tag (:user data)) " (" id \)))
    @(bulk-overwrite-global-application-commands! msg-ch id (:commands config))))
