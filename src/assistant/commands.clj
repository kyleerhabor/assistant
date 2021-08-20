(ns assistant.commands
  (:require [discljord.messaging :refer [create-interaction-response!]]
            [discljord.messaging.specs :refer [interaction-response-types]]))

(def wikimedia-user-agent (str "AssistantBot/0.1.0"
                               " (https://github.com/KyleErhabor/assistant; kyleerhabor@gmail.com)"
                               " Clojure/" (clojure-version)))

(defn wikipedia
  "Search Wikipedia."
  [msg-ch interaction]
  @(create-interaction-response! msg-ch
                                 (:id interaction)
                                 (:token interaction)
                                 (:channel-message-with-source interaction-response-types)
                                 :data {:content "Say hello to Wikipedia."}))
