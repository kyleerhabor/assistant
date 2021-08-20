(ns assistant.commands
  (:require [clojure.string :refer [join]]
            [assistant.settings :refer [deps]]
            [clj-http.client :as client]
            [discljord.formatting :refer [bold]]
            [discljord.messaging :refer [create-interaction-response!]]
            [discljord.messaging.specs :refer [interaction-response-types]]
            [hickory.core :as hick]
            [hickory.select :as sel]))

(def wp-user-agent (str "AssistantBot/0.1.0"
                        " (https://github.com/KyleErhabor/assistant; kyleerhabor@gmail.com)"
                        " Clojure/" (clojure-version) ";"
                        " clj-http/" (:mvn/version (get (:deps deps) 'clj-http/clj-http))))

(defn wp-snippet-content
  "Converts HTML in an article snippet into Markdown."
  [snippet]
  (join (for [fragment (hick/parse-fragment snippet)]
          (if (instance? org.jsoup.nodes.TextNode fragment)
            (str fragment)
            (->> fragment
                 hick/as-hickory
                 (sel/select (sel/child (sel/and (sel/tag :span)
                                                 (sel/class :searchmatch))))
                 first
                 :content
                 first
                 bold)))))

; Commands

(defn wikipedia [msg-ch interaction query]
  (let [body (:body (client/get "https://en.wikipedia.org/w/api.php" {:as :json
                                                                      :headers {:User-Agent wp-user-agent}
                                                                      :query-params {:action "query"
                                                                                     :format "json"
                                                                                     :list "search"
                                                                                     :srsearch query
                                                                                     :srnamespace 0}}))]
    @(create-interaction-response! msg-ch
                                   (:id interaction)
                                   (:token interaction)
                                   (:channel-message-with-source interaction-response-types)
                                   :data {:embeds [{:title "Results"
                                                    :fields (for [result (-> body :query :search)]
                                                              {:name (:title result)
                                                               :value (wp-snippet-content (:snippet result))})}]})))
