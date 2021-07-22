(ns assistant.commands.core
  (:require [assistant.state :refer [state]]
            [discljord.connections :refer [disconnect-bot!]]
            [discljord.messaging :refer [create-message!]]))

(defn exit
  {:aliases ["shutdown"]}
  [message _]
  @(create-message! (:message @state) (:channel-id message)
                   :content "Exiting.")
  (disconnect-bot! (:connection @state)))
