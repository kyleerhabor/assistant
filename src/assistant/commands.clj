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

(defn respond
  "Responds to an interaction with the connection, ID, and token supplied."
  [conn interaction & args]
  (apply create-interaction-response! conn (:id interaction) (:token interaction) args))

(defn ^:command poll
  "Creates a poll."
  {:options [{:type (:string command-option-types)
              :name "issue"
              :description "The message to display."
              :required true}]}
  [conn interaction]
  (respond conn interaction (:channel-message-with-source interaction-response-types)
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

(defn tagq
  "Searches for a tag by its name and the bound environment. `env` should be a map with a :guild or :user key."
  [name env]
  (xt/q (xt/db db/node) '{:find [(pull ?e [:xt/id :tag/name :tag/content])]
                          :in [?name ?guild ?user]
                          :where [[?e :tag/name ?name]
                                  (or (and [?e :tag/guild ?guild]
                                           [(any? ?user)])
                                      (and [?e :tag/user ?user]
                                           [(any? ?guild)]))]} name (:guild env) (:user env)))

(defn tag-autocomplete [conn interaction name env]
  (respond conn interaction 8
           :data {:choices (for [tag (let [name (str/lower-case name)]
                                       (xt/q (xt/db db/node)
                                             '{:find [?tag-name]
                                               :in [?name ?guild ?user]
                                               :where [[?e :tag/name ?tag-name]
                                                       [(str/lower-case ?tag-name) ?lower-tag-name]
                                                       [(str/includes? ?lower-tag-name ?name)]
                                                       (or (and [?e :tag/guild ?guild]
                                                                [(any? ?user)])
                                                           (and [?e :tag/user ?user]
                                                                [(any? ?guild)]))]} name (:guild env) (:user env)))
                                 :let [tag (first tag)]]
                             {:name tag
                              :value tag})}))

(defn ^:command tag
  "Tag facilities for pasting and managing common responses."
  {:options [{:type (:sub-command command-option-types)
              :name "get"
              :description "Displays a tag."
              :options [{:type (:string command-option-types)
                         :name "name"
                         :description "The name of the tag."
                         :required true
                         :autocomplete true}]}
             {:type (:sub-command command-option-types)
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
             {:type (:sub-command command-option-types)
              :name "delete"
              :description "Deletes a tag."
              :options [{:type (:string command-option-types)
                         :name "name"
                         :description "The name of the tag."
                         :required true
                         :autocomplete true}]}]}
  [conn interaction]
  (let [subcommand (first (:options (:data interaction)))
        guild (:guild-id interaction)
        user (:id (:user interaction))
        name (first (:options subcommand))
        content (second (:options subcommand))]
    (case (:name subcommand)
      "get" (if (:focused name)
              (tag-autocomplete conn interaction (:value name) {:guild guild
                                                                :user user})
              (respond conn interaction (:channel-message-with-source interaction-response-types)
                       :data (if-let [tag (first (first (tagq (:value name) {:guild guild
                                                                             :user user})))]
                               {:embeds [{:title (:tag/name tag)
                                          :description (:tag/content tag)}]}
                               {:content "Tag not found."})))
      "create" (respond conn interaction (:channel-message-with-source interaction-response-types)
                        :data {:content (if (seq (tagq (:value name) {:guild guild
                                                                      :user user}))
                                          "Tag already exists."
                                          (do (xt/submit-tx db/node [[::xt/put (cond-> {:xt/id (db/id)
                                                                                        :tag/name (:value name)
                                                                                        :tag/content (:value content)}
                                                                                 guild (assoc :tag/guild guild)
                                                                                 user (assoc :tag/user user))]])
                                              "Tag created."))})
      "delete" (if (:focused name)
                 (tag-autocomplete conn interaction (:value name) {:guild guild
                                                                   :user user})
                 (respond conn interaction (:channel-message-with-source interaction-response-types)
                          :data {:content (if-let [tag (first (first (tagq (:value name) {:guild guild
                                                                                          :user user})))]
                                            (do (xt/submit-tx db/node [[::xt/delete (:xt/id tag)]])
                                                "Tag deleted.")
                                            "Tag not found.")})))))

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
    (respond conn interaction (:channel-message-with-source interaction-response-types)
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
