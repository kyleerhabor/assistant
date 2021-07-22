(ns assistant.events
  (:require [assistant.commands :refer [commands]]
            [assistant.state :refer [config state]]
            [clojure.string :as string]
            [discljord.messaging :refer [create-message!]]))

(defmulti handler
  "Handler for Discord API events."
  (fn [type _] type))

(defmethod handler :message-create
  [_ message]
  (when-not (-> message :author :bot)
    (let [[prefix command & args] (string/split (:content message) #"\s+")]
      (when (and prefix command
                 (some #(= % prefix) (:prefixes config)))
        (when-let [command ((keyword command) commands)]
          (when-let [response (command message (or args []))]
            (println response)
            @(create-message! (:message @state) (:channel-id message)
                              :content response)))))))

(defmethod handler :default
  [_ _])
