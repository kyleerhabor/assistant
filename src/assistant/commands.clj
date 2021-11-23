(ns assistant.commands
  (:require [clojure.set :refer [rename-keys]]
            [clojure.string :as str]
            [assistant.db :as db]
            [assistant.settings :refer [deps]]
            [clj-http.client :as client]
            [discljord.formatting :as fmt]
            [discljord.messaging :refer [create-interaction-response!]]
            [discljord.messaging.specs :refer [command-option-types interaction-response-types]]
            [hickory.core :as hick]
            [hickory.select :as sel]
            [xtdb.api :as xt]))

(def wm-user-agent (str "AssistantBot/0.1.0 (https://github.com/KyleErhabor/assistant; kyleerhabor@gmail.com)"
                        " Clojure/" (clojure-version) ";"
                        " clj-http/" (-> deps :deps ('clj-http/clj-http) :mvn/version)))

(defn wp-snippet-content
  "Converts HTML in an article snippet into Markdown. Currently only transforms `<span class=searchmatch ...>` into
   `**...**`."
  [snippet]
  (str/join (for [fragment (hick/parse-fragment snippet)]
              (if (instance? org.jsoup.nodes.TextNode fragment)
                (str fragment)
                (->> fragment
                     hick/as-hickory
                     (sel/select (sel/child (sel/and (sel/tag :span)
                                                     (sel/class :searchmatch))))
                     first
                     :content
                     first
                     fmt/bold)))))

(defn ^:command poll
  "Creates a poll."
  {:options [{:type (:string command-option-types)
              :name "issue" ; Could also be called message, question, etc.
              :description "The message to display."
              :required true}]}
  [conn interaction]
  @(create-interaction-response! conn
                                 (:id interaction)
                                 (:token interaction)
                                 (:channel-message-with-source interaction-response-types)
                                 :data {:content (:value (first (:options (:data interaction))))
                                        :components [{:type 1
                                                      :components [{:type 3
                                                                    :custom_id "poll-responses"
                                                                    ;; TODO: Display the actual total.
                                                                    :options [{:label "Yes"
                                                                               :value "yes"
                                                                               :description "0 votes"}
                                                                              {:label "No"
                                                                               :value "no"
                                                                               :description "0 votes"}]}]}]}))

(defn ^:command tag
  "Tag facilities for pasting and managing common responses."
  {:options [{:type (:sub-command command-option-types)
              :name "create"
              :description "Creates a tag."
              :options [{:type (:string command-option-types)
                         :name "name"
                         :description "The name of the tag."
                         :required true}
                        {:type (:string command-option-types)
                         :name "content"
                         :description "The contents of the tag."
                         :required true}]}
             #_{:type (:string command-option-types)
                :name "name"
                :description "The name of the tag to paste."
                :required true}]}
  [conn interaction]
  (create-interaction-response! conn
                                (:id interaction)
                                (:token interaction)
                                (:channel-message-with-source interaction-response-types)
                                :data {:content (let [opts (:options (first (:options (:data interaction))))
                                                      name (:value (first opts))
                                                      guild-id (:guild-id interaction)
                                                      user-id (:id (:user interaction))]
                                                  ;; If the tag name provided by the user already exists in the current context.
                                                  (if (seq (xt/q (xt/db db/node) '{:find [?e]
                                                                                   :in [?name ?guild ?user]
                                                                                   :where [[?e :tag/name ?name]
                                                                                           (or (and [?e :tag/guild ?guild]
                                                                                                    [(any? ?user)])
                                                                                               (and [?e :tag/user ?user]
                                                                                                    [(any? ?guild)]))]} name guild-id user-id))
                                                    "Tag already exists."
                                                    (do
                                                      (xt/submit-tx db/node [[::xt/put (cond-> {:xt/id (db/id)
                                                                                                :tag/name name
                                                                                                :tag/content (:value (second opts))}
                                                                                         guild-id (assoc :tag/guild guild-id)
                                                                                         user-id (assoc :tag/user user-id))]])
                                                      "Tag created.")))}))

(defn ^:command wikipedia
  "Search Wikipedia."
  {:options [{:type (:string command-option-types)
              :name "query"
              :description "The terms to search Wikipedia with."
              :required true}]}
  [conn interaction]
  (let [body (:body (client/get "https://en.wikipedia.org/w/api.php" {:as :json
                                                                      :headers {:User-Agent wm-user-agent}
                                                                      :query-params {:action "query"
                                                                                     :format "json"
                                                                                     :list "search"
                                                                                     :srsearch (:value (first (:options (:data interaction))))
                                                                                     :srnamespace 0}}))]
    @(create-interaction-response! conn
                                   (:id interaction)
                                   (:token interaction)
                                   (:channel-message-with-source interaction-response-types)
                                   :data {:embeds [{:title "Results"
                                                    :fields (for [result (-> body :query :search)]
                                                              {:name (:title result)
                                                               :value (wp-snippet-content (:snippet result))})}]})))

(def commands (reduce-kv (fn [m k v]
                           (let [meta (meta v)]
                             (if (:command meta)
                               (assoc m (keyword k) (-> meta
                                                        (select-keys [:autocomplete :channel-types :choices 
                                                                      :default-permission :doc :max-value :min-value
                                                                      :options :required :type])
                                                        (rename-keys {:doc :description})
                                                        (assoc :command v)))
                               m))) {} (ns-publics *ns*)))

(def discord-commands (reduce-kv #(conj %1 (-> %3
                                               (assoc :name %2)
                                               (dissoc :command))) [] commands))
