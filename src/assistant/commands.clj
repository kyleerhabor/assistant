(ns assistant.commands
  "Command facilities for Assistant.
   
   Commands are declared as functions with `^:command`. The function name, documentation, and metadata is used to
   extract the data about a command to the `commands` and `discord-commands` vars. There's no special handling for
   subcommands or subcommand groups. Conventionally, they're declared as regular functions following the format 
   command-group-subcommand. If the subcommand doesn't have a group, it should be excluded from the name. For example,
   tag-get rather than tag-?-get."
  (:refer-clojure :exclude [range])
  (:require [clojure.core :as c]
            [clojure.core.async :refer [>! <! chan go timeout]]
            [clojure.edn :as edn]
            [clojure.set :refer [rename-keys]]
            [clojure.string :as str]
            [clojure.walk :refer [postwalk]]
            [assistant.db :as db]
            [clj-http.client :as http]
            [datascript.core :as d]
            [discljord.cdn :as ds.cdn]
            [discljord.formatting :as ds.fmt]
            [discljord.messaging :refer [bulk-delete-messages! create-interaction-response!
                                         delete-message! delete-original-interaction-response! get-guild!
                                         get-channel-messages!]]
            [discljord.messaging.specs :refer [command-option-types interaction-response-types]]
            [discljord.permissions :as ds.p]
            [hickory.core :as hick]
            [hickory.select :as hick.s]
            [tick.core :as tick]))

(defn respond
  "Responds to an interaction with the connection, ID, and token supplied."
  [conn interaction & args]
  (apply create-interaction-response! conn (:id interaction) (:token interaction) args))

(defn resize-image
  "Resizes an image if `url` is not `nil`."
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
              :description "The maximum size of the avatar."
              :choices (map #(identity {:name (str %)
                                        :value %}) [16 32 64 128 256 512 1024 2048 4096])}]}
  [conn interaction]
  (let [user (get (:users (:resolved (:data interaction)))
                  (:value (:user (:options (:data interaction)))))
        size (or (:value (:size (:options (:data interaction)))) 4096)]
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
      (let [amount (:value (:amount (:options (:data interaction))))
            user (:value (:user (:options (:data interaction))))
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

(defn ^:command range
  "Picks a random number from a range."
  {:options [{:type (:integer command-option-types)
              :name "max"
              :description "The highest number."
              :required true}
             {:type (:integer command-option-types)
              :name "min"
              :description "The lowest number (defaults to 1)."}
             {:type (:integer command-option-types)
              :name "amount"
              :description "The amount of numbers to pick (defaults to 1). May return less than requested."
              :min_value 1}]}
  [conn interaction]
  (respond conn interaction (:channel-message-with-source interaction-response-types)
           :data {:content (let [opts (:options (:data interaction))
                                 high (:value (:max opts))
                                 low (or (:value (:min opts)) 1)
                                 amount (min (or (:value (:amount opts)) 1) (- high low))
                                 ;; To use (shuffle ...) here would be suicidal as it's not lazy. Lazy processing is 
                                 ;; imperative to keeping this command fast and efficient. Instead, `random-sample` is
                                 ;; preferrable.
                                 nums (take amount (random-sample (* (/ 1 (- high low)) amount)
                                                                  (c/range low (inc high))))]
                             (if (seq nums)
                               (str (str/join " " nums) (let [rem (- amount (count nums))]
                                                          (if-not (= 0 rem)
                                                            (str " (" (ds.fmt/bold rem)
                                                                 " number" (if-not (= 1 rem) \s) " missing)"))))
                               "No numbers."))}))

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

(defn tagq*
  "Queries the database for a tag by its `name`. `env` should be either `:tag/guild` or `:tag/user` with `id` being used
   to match the value."
  [name env id]
  (d/q '[:find (pull ?e [:db/id :tag/name :tag/content]) .
         :in $ ?name ?env ?id
         :where [?e :tag/name ?name]
                [?e ?env ?id]] (db/read) name env id))

(defn tagq [name interaction]
  (tagq* name (if (:guild-id interaction)
               :tag/guild
               :tag/user) (or (:guild-id interaction) (:id (:user interaction)))))

(defn tag-autocomplete
  "Handles tag autocompletion searching by name."
  [conn interaction name]
  (respond conn interaction 8
           :data {:choices (let [tag (tagq name interaction)]
                             ;; TODO: Introduce proper autocompletion.
                             [{:name tag
                               :value tag}])}))

(defn tag-get
  "Subcommand for retrieving a tag by name."
  [conn interaction]
  (println interaction)
  (let [name (:name (:options (:get (:options (:data interaction)))))]
    (if (:focused name)
      (tag-autocomplete conn interaction (:value name))
      (respond conn interaction (:channel-message-with-source interaction-response-types)
               :data (if-let [tag (tagq (:value name) interaction)]
                       {:embeds [{:title (:tag/name tag)
                                  :description (:tag/content tag)}]}
                       {:content "Tag not found."})))))

(defn tag-create
  "Subcommand for creating a tag with a name and content."
  [conn interaction]
  (go
    (let [opts (:options (:create (:options (:data interaction))))
          name (:value (:name opts))
          content (:value (:content opts))]
      (respond conn interaction (:channel-message-with-source interaction-response-types)
               :data {:content (if (tagq name interaction)
                                 "Tag already exists."
                                 (do (db/transact [(let [gid (:guild-id interaction)
                                                         uid (:id (:user interaction))]
                                                     (cond-> {:tag/name name
                                                              :tag/content content}
                                                       gid (assoc :tag/guild gid)
                                                       uid (assoc :tag/user uid)))])
                                     "Tag created."))}))))

(defn tag-delete
  "Subcommand for deleting a tag by name."
  [conn interaction]
  (let [name (:name (:options (:delete (:options (:data interaction)))))]
    (if (:focused name)
      (tag-autocomplete conn interaction (:value name))
      (respond conn interaction (:channel-message-with-source interaction-response-types)
               :data {:content (if-let [tag (tagq (:value name) interaction)]
                                 (do (db/transact [[:db.fn/retractEntity (:db/id tag)]])
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
  (case (key (first (:options (:data interaction))))
    :get (tag-get conn interaction)
    :create (tag-create conn interaction)
    :delete (tag-delete conn interaction)))

(defonce trivia-categories (reduce #(assoc %1 (:name %2) (:id %2)) {}
                                   ;; In case Open Trivia DB adds more than 25.
                                   (take 25 (:trivia_categories (:body (http/get "https://opentdb.com/api_category.php" {:as :json}))))))

(defn ^:command trivia
  "Runs a trivia."
  {:options [{:type (:string command-option-types)
              :name "category"
              :description "The category the question belongs to."
              :choices (map #(zipmap [:name :value] (repeat %)) (keys trivia-categories))}
             {:type (:string command-option-types)
              :name "difficulty"
              :description "The difficulty of the question."
              :choices (map #(zipmap [:name :value] (repeat %)) ["Easy" "Medium" "Hard"])}
             {:type (:string command-option-types)
              :name "type"
              :description "The amount of answers the question should have."
              :choices (map #(zipmap [:name :value] (repeat %)) ["Multiple Choice" "True/False"])}]}
  [conn interaction]
  (go
    (respond conn interaction (:channel-message-with-source interaction-response-types)
             :data (if (= 3 (:type interaction))
                     {:content (let [data (:data interaction)
                                     answer (first (:values data))]
                                 (str answer "—" (if (= answer (:custom-id data))
                                                   "Correct! 🎉"
                                                   "Wrong. 😔")))
                      :flags (bit-shift-left 1 6)}
                     (let [res (chan)
                           category (:value (:category (:options (:data interaction))))
                           difficulty (:value (:difficulty (:options (:data interaction))))
                           type (:value (:type (:options (:data interaction))))]
                       (http/get "https://opentdb.com/api.php"
                                 {:as :json
                                  :async? true
                                  :query-params {:amount 1
                                                 :category (trivia-categories category)
                                                 :difficulty (if difficulty
                                                               (str/lower-case difficulty))
                                                 :type (case type
                                                         "Multiple Choice" "multiple"
                                                         "True/False" "boolean"
                                                         nil)}}
                                 #(go (>! res %))
                                 (constantly nil))
                       (let [trivia (first (:results (postwalk #(if (string? %)
                                                                  ;; For some reason, Open Trivia DB corrupts the HTML
                                                                  ;; entity encoding.
                                                                  (.text (first (hick/parse-fragment (str/replace % "amp;" ""))))
                                                                  %) (:body (<! res)))))]
                         {:content (:question trivia)
                          :components [{:type 1
                                        :components [{:type 3
                                                      :custom_id (:correct_answer trivia)
                                                      :options (for [answer (if (= "boolean" (:type trivia))
                                                                              ;; It's often annoying to have boolean
                                                                              ;; answers shuffled, so we're going to
                                                                              ;; keep them in the same order. If you've
                                                                              ;; ever played Kahoot, you know the pain.
                                                                              ["True" "False"]
                                                                              (shuffle (conj (:incorrect_answers trivia) (:correct_answer trivia))))]
                                                                 {:label answer
                                                                  :value answer})}]}]}))))))

(def wm-user-agent
  "A user agent string conforming to the [Wikimedia User-Agent policy](https://meta.wikimedia.org/wiki/User-Agent_policy)."
  (str "AssistantBot/1.1.0 (https://github.com/KyleErhabor/assistant; kyleerhabor@gmail.com)"
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
                     (hick.s/select (hick.s/child (hick.s/and (hick.s/tag :span)
                                                     (hick.s/class :searchmatch))))
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
    (let [res (chan)
          query (:value (:query (:options (:data interaction))))]
      (http/get "https://en.wikipedia.org/w/api.php" {:as :json
                                                      :async? true
                                                      :headers {:User-Agent wm-user-agent}
                                                      :query-params {:action "query"
                                                                     :format "json"
                                                                     :list "search"
                                                                     :srsearch query
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
                                            (assoc :fn v)))
                   m))) {} (ns-publics *ns*)))

(def discord-commands
  "A vector of command maps conforming to the [Bulk Overwrite Global Application Commands](https://discord.com/developers/docs/interactions/application-commands#bulk-overwrite-global-application-commands)
   endpoint."
  (reduce-kv #(conj %1 (-> %3
                           (assoc :name %2)
                           (dissoc :fn))) [] commands))
