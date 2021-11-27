(ns assistant.commands
  "Command facilities for Assistant.
   
   Commands are declared as functions with :command set as metadata. The function name, documentation, and metadata is
   used to extract the data about a command to the `commands` and `discord-commands` vars. There's no special handling
   for subcommands or subcommand groups. Conventionally, they're declared as regular functions following the format
   command-group-subcommand. If the subcommand doesn't have a group, it should be excluded from the name. For example,
   `tag-get` rather than `tag-?-get`."
  (:require [clojure.core.async :refer [>! <! chan go timeout]]
            [clojure.edn :as edn]
            [clojure.set :refer [rename-keys]]
            [clojure.string :as str]
            [assistant.db :as db]
            [clj-http.client :as client]
            [discljord.cdn :as ds.cdn]
            [discljord.formatting :as ds.fmt]
            [discljord.messaging :refer [bulk-delete-messages! create-interaction-response!
                                         delete-message! delete-original-interaction-response! get-guild!
                                         get-channel-messages!]]
            [discljord.messaging.specs :refer [command-option-types interaction-response-types]]
            [discljord.permissions :as ds.p]
            [hickory.core :as hick]
            [hickory.select :as sel]
            [tick.core :as tick]
            [xtdb.api :as xt]))

(defn respond
  "Responds to an interaction with the connection, ID, and token supplied."
  [conn interaction & args]
  (apply create-interaction-response! conn (:id interaction) (:token interaction) args))

(defn resize-image
  "Resizes an image if not `nil`."
  [url]
  (and url (ds.cdn/resize url 4096)))

(defn ^:command avatar
  "Gets a user's avatar."
  {:options [{:type (:user command-option-types)
              :name "user"
              :description "The user to get the avatar of."
              :required true}
             {:type (:integer command-option-types)
              :name "size"
              :description "The size of the avatar."
              :choices (map #(identity {:name (str %)
                                        :value %}) [16 32 64 128 256 512 1024 2048 4096])}]}
  [conn interaction]
  (let [user (get (:users (:resolved (:data interaction)))
                  (:value (first (:options (:data interaction)))))
        size (or (:value (second (:options (:data interaction)))) 4096)]
    (respond conn interaction (:channel-message-with-source interaction-response-types)
             :data {:content (ds.cdn/resize (ds.cdn/effective-user-avatar user) size)})))

(defn ^:command purge
  "Deletes messages from a channel."
  {:options [{:type (:integer command-option-types)
              :name "amount"
              :description "The number of messages to delete."
              :required true
              :min_value 2
              :max_value 100}
             {:type (:user command-option-types)
              :name "user"
              ;; Although the user may not be a member of the guild, they may have left messages in the channel.
              :description "The user to delete messages from."}]}
  [conn interaction]
  (go
    (if (ds.p/has-permission-flag? :manage-messages (:permissions (:member interaction)))
      (let [amount (:value (first (:options (:data interaction))))
            user (:value (second (:options (:data interaction))))
            msgs (transduce (comp (filter #(>= 14 (tick/days (tick/between (tick/instant (:timestamp %))
                                                                           (tick/instant)))))
                                  (filter (complement :pinned))
                                  (filter #(or (nil? user) (= user (:id (:author %)))))
                                  (map :id))
                            conj
                            (<! (get-channel-messages! conn (:channel-id interaction)
                                                       :limit amount)))]
        (when (<! (respond conn interaction (:channel-message-with-source interaction-response-types)
                           :data {:content (or (case (count msgs)
                                                 0 "No messages to purge."
                                                 1 (if (<! (delete-message! conn (:channel-id interaction) (first msgs)))
                                                     "Deleted one message.")
                                                 (if (<! (bulk-delete-messages! conn (:channel-id interaction) msgs))
                                                   "Purge successful."))
                                               "Purge failed.")}))
          (<! (timeout 2000))
          (delete-original-interaction-response! conn (:application-id interaction) (:token interaction))))
      (respond conn interaction (:channel-message-with-source interaction-response-types)
               :data {:content "Missing Manage Messages permission."}))))

(defn ^:command server
  "Gets information about the server."
  [conn interaction]
  (go
    (respond conn interaction (:channel-message-with-source interaction-response-types)
             :data {:embeds [(let [guild (<! (get-guild! conn (:guild-id interaction)
                                                         :with-counts true))
                                   afk (:afk-channel-id guild)]
                               {:title (:name guild)
                                :url (:vanity-url-code guild)
                                :description (:description guild)
                                :thumbnail {:url (resize-image (ds.cdn/guild-icon guild))}
                                :image {:url (resize-image (ds.cdn/guild-banner guild))}
                                :fields (cond-> [{:name "Owner"
                                                  :value (ds.fmt/mention-user (:owner-id guild))
                                                  :inline true}
                                                 {:name "Members"
                                                  :value (str "~" (:approximate-member-count guild))
                                                  :inline true}
                                                 {:name "Roles"
                                                  :value (count (:roles guild))
                                                  :inline true}
                                                 {:name "Emojis"
                                                  :value (count (:emojis guild))
                                                  :inline true}]
                                          afk (conj {:name "AFK Channel"
                                                     :value (let [mins (tick/minutes (tick/new-duration (:afk-timeout guild) :seconds))]
                                                              (str (ds.fmt/mention-channel afk)
                                                                   " ("
                                                                   (if (= mins 60)
                                                                     "1 hour"
                                                                     (str mins " minute" (if-not (= mins 1) \s)))
                                                                   \)))
                                                     :inline true}))})]})))

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

(defn tag-autocomplete
  "Handles tag autocompletion searching by name."
  [conn interaction name env]
  (respond conn interaction 8
           :data {:choices (for [tag (xt/q (xt/db db/node)
                                           '{:find [?tag-name]
                                             :in [?name ?guild ?user]
                                             :where [[?e :tag/name ?tag-name]
                                                     [(clojure.string/lower-case ?tag-name) ?lower-tag-name]
                                                     [(clojure.string/includes? ?lower-tag-name ?name)]
                                                     (or (and [?e :tag/guild ?guild]
                                                              [(any? ?user)])
                                                         (and [?e :tag/user ?user]
                                                              [(any? ?guild)]))]
                                             :limit 25}
                                           (str/lower-case name) (:guild env) (:user env))
                                 :let [tag (first tag)]]
                             {:name tag
                              :value tag})}))

(defn tag-env
  "Formats an interaction suitable for a tag environment (guild > user)."
  [interaction]
  {:guild (:guild-id interaction)
   :user (:id (:user interaction))})

(defn tag-get
  "Subcommand for retrieving a tag by name."
  [conn interaction]
  (let [env (tag-env interaction)
        name (get-in interaction [:data :options 0 :options 0])]
    (if (:focused name)
      (tag-autocomplete conn interaction (:value name) env)
      (respond conn interaction (:channel-message-with-source interaction-response-types)
               :data (if-let [tag (first (first (tagq (:value name) env)))]
                       {:embeds [{:title (:tag/name tag)
                                  :description (:tag/content tag)}]}
                       {:content "Tag not found."})))))

(defn tag-create
  "Subcommand for creating a tag with a name and content."
  [conn interaction]
  (let [env (tag-env interaction)
        options (get-in interaction [:data :options 0 :options])
        name (get-in options [0 :value])
        content (get-in options [1 :value])]
    (respond conn interaction (:channel-message-with-source interaction-response-types)
             :data {:content (if (seq (tagq name (tag-env interaction)))
                               "Tag already exists."
                               (do (xt/submit-tx-async db/node [[::xt/put (cond-> {:xt/id (db/id)
                                                                                   :tag/name name
                                                                                   :tag/content content}
                                                                            (:guild env) (assoc :tag/guild (:guild env))
                                                                            (:user env) (assoc :tag/user (:user env)))]])
                                   "Tag created."))})))

(defn tag-delete
  "Subcommand for deleting a tag by name."
  [conn interaction]
  (let [env (tag-env interaction)
        name (get-in interaction [:data :options 0 :options 0])]
    (if (:focused name)
      (tag-autocomplete conn interaction (:value name) env)
      (respond conn interaction (:channel-message-with-source interaction-response-types)
               :data {:content (if-let [tag (first (first (tagq (:value name) env)))]
                                 (do (xt/submit-tx-async db/node [[::xt/delete (:xt/id tag)]])
                                     "Tag deleted.")
                                 "Tag not found.")}))))

(defn ^:command tag
  "Facilities for getting and managing message responses."
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
  (case (get-in interaction [:data :options 0 :name])
    "get" (tag-get conn interaction)
    "create" (tag-create conn interaction)
    "delete" (tag-delete conn interaction)))

(def wm-user-agent
  "A user agent string conforming to the [Wikimedia User-Agent policy](https://meta.wikimedia.org/wiki/User-Agent_policy)."
  (str "AssistantBot/0.1.0 (https://github.com/KyleErhabor/assistant; kyleerhabor@gmail.com)"
                        " Clojure/" (clojure-version) ";"
                        " clj-http/" (-> (edn/read-string (slurp "deps.edn"))
                                         :deps
                                         ('clj-http/clj-http)
                                         :mvn/version)))

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
                     ds.fmt/bold)))))

(defn ^:command wikipedia
  "Searches Wikipedia."
  {:options [{:type (:string command-option-types)
              :name "query"
              :description "The terms to search for."
              :required true}]}
  [conn interaction]
  (go
    (let [res (chan)]
      (client/get "https://en.wikipedia.org/w/api.php"
                  {:as :json
                   :async? true
                   :headers {:User-Agent wm-user-agent}
                   :query-params {:action "query"
                                  :format "json"
                                  :list "search"
                                  :srsearch (:value (first (:options (:data interaction))))
                                  :srnamespace 0}}
                  #(go (>! res %))
                  (constantly nil))
      (respond conn interaction (:channel-message-with-source interaction-response-types)
               :data {:embeds [{:title "Results"
                                :fields (for [result (:search (:query (:body (<! res))))]
                                          {:name (:title result)
                                           :value (wp-snippet-content (:snippet result))})}]}))))

(def commands
  "A map of keywordized command names to command details conforming to the [Create Global Application Command](https://discord.com/developers/docs/interactions/application-commands#create-global-application-command)
   endpoint."
  (reduce-kv (fn [m k v]
               (let [meta (meta v)]
                 (if (:command meta)
                   (assoc m (keyword k) (-> meta
                                            (select-keys [:autocomplete :channel-types :choices
                                                          :default-permission :doc :max-value :min-value
                                                          :options :required :type])
                                            (rename-keys {:doc :description})
                                            (assoc :command v)))
                   m))) {} (ns-publics *ns*)))

(def discord-commands
  "A vector of command maps conforming to the [Bulk Overwrite Global Application Commands](https://discord.com/developers/docs/interactions/application-commands#bulk-overwrite-global-application-commands)
   endpoint."
  (reduce-kv #(conj %1 (-> %3
                           (assoc :name %2)
                           (dissoc :command))) [] commands))
