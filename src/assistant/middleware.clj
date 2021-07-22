(ns assistant.middleware
  (:require [assistant.state :refer [state]]
            [discljord.messaging :refer [get-current-application-information!]]))

(defn sysop
  "Checks if the message author is a system operator for the bot."
  [command]
  (fn [message args]
    (let [app @(get-current-application-information! (:message @state))
          author-id (-> message :author :id)]
      (if (or (and (some-> app :owner :id) author-id)
              (if-let [members (some-> app :team :members)]
                (some #(= (-> % :user :id) author-id) members)))
        (command message args)
        "You need to be a system operator to run this command."))))
