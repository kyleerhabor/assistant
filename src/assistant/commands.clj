(ns assistant.commands
  (:require [assistant.settings :refer [deps]]
            [clj-http.client :as client]
            [discljord.messaging :refer [create-interaction-response!]]
            [discljord.messaging.specs :refer [interaction-response-types]]))

(def wikimedia-user-agent (str "AssistantBot/0.1.0"
                               " (https://github.com/KyleErhabor/assistant; kyleerhabor@gmail.com)"
                               " Clojure/" (clojure-version) ";"
                               " clj-http/" (:mvn/version (get (:deps deps) 'clj-http/clj-http))))

(defn wikipedia
  "Search Wikipedia."
  [msg-ch interaction query]
  (let [_ (:body (client/get "https://en.wikipedia.org/w/api.php" {:as :json
                                                                      :headers {:User-Agent wikimedia-user-agent}
                                                                      :query-params {:action "query"
                                                                                     :format "json"
                                                                                     :list "search"
                                                                                     :srsearch query
                                                                                     :srnamespace 0}}))])
  @(create-interaction-response! msg-ch
                                 (:id interaction)
                                 (:token interaction)
                                 (:channel-message-with-source interaction-response-types)
                                 :data {:content "Say hello to Wikipedia."}))
