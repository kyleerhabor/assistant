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
            [discljord.messaging :refer [bulk-delete-messages! create-guild-ban! create-interaction-response!
                                         delete-message! delete-original-interaction-response! get-guild!
                                         get-channel-messages!]]
            [discljord.messaging.specs :refer [command-option-types interaction-response-types]]
            [discljord.permissions :as ds.p]
            [hickory.core :as hick]
            [hickory.select :as hick.s]
            [tick.core :as tick]))

(defn interaction->value [interaction k]
  (:value (k (:options (:data interaction)))))

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

(defn ban-duration [interaction units]
  (tick/new-period (or (interaction->value interaction units) 0) units))

(defn ^:command ban
  "Bans a user."
  {:options [{:type (:user command-option-types)
              :name "user"
              :description "The user to ban."
              :required true}
             {:type (:string command-option-types)
              :name "reason"
              :description "The reason for the ban."}
             {:type (:integer command-option-types)
              :name "seconds"
              :description "The number of seconds to ban the user for."}
             {:type (:integer command-option-types)
              :name "minutes"
              :description "The number of minutes to ban the user for."}
             {:type (:integer command-option-types)
              :name "hours"
              :description "The number of hours to ban the user for."}
             {:type (:integer command-option-types)
              :name "days"
              :description "The number of days to ban the user for."}
             {:type (:integer command-option-types)
              :name "weeks"
              :description "The number of weeks to ban the user for."}
             {:type (:integer command-option-types)
              :name "months"
              :description "The number of months to ban the user for."}
             {:type (:integer command-option-types)
              :name "years"
              :description "The number of years to ban the user for."}
             #_{:type (:integer command-option-types)
              :name "messages"
              :description "Deletes messages younger than the number of days specified."
              :min_value 1
              :max_value 7}]}
  [conn interaction]
  (go
    (let [user (interaction->value interaction :user)
          reason (interaction->value interaction :reason)
          seconds (interaction->value interaction :seconds)
          minutes (interaction->value interaction :minutes)
          hours (interaction->value interaction :hours)
          days (interaction->value interaction :days)
          weeks (interaction->value interaction :weeks)
          months (interaction->value interaction :months)
          years (interaction->value interaction :years)
          duration (cond-> (tick/new-duration 0 :seconds)
                     seconds (tick/+ (tick/new-duration seconds :seconds))
                     minutes (tick/+ (tick/new-duration minutes :minutes))
                     hours (tick/+ (tick/new-duration hours :hours))
                     days (tick/+ (tick/new-duration days :days))
                     weeks (tick/+ (tick/new-duration (tick/days (* 7 weeks)) :days))
                     months (tick/+ (tick/new-duration (tick/months months) :days))
                     years (tick/+ (tick/new-duration years :days)))
          #_(tick/+ (ban-duration interaction :seconds)
                           (ban-duration interaction :minutes)
                           (ban-duration interaction :hours)
                           (ban-duration interaction :days)
                           (ban-duration interaction :weeks)
                           (ban-duration interaction :months)
                           (ban-duration interaction :years))
          #_message-days #_(interaction->value interaction :user)]
      (<! (create-guild-ban! conn (:guild-id interaction) user
                             :audit-reason reason))
      (respond conn interaction (:channel-message-with-source interaction-response-types)
               :data {:content "Banned."}))))

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
                                 ;; The output would've been clamped by 600, so there's no point in collecting the total
                                 ;; beyond that.
                                 amount (min 600 (or (:value (:amount opts)) 1))
                                 s (str/join " " (if (>= amount (- high low))
                                                   ;; There's no point in running the randomizer (loop) if we know all
                                                   ;; the numbers. Unfortunately, this optimization trick isn't useful
                                                   ;; for values lower (e.g. 99 of 100).
                                                   (c/range low (inc high))
                                                   (let [amount (min amount (- high low))]
                                                     (sort (loop [nums #{}]
                                                             (if (= amount (count nums))
                                                               nums
                                                               (recur (conj nums (+ (long (rand (- (inc high) low)))
                                                                                    low)))))))))]
                             (cond
                               (= 0 (count s)) "No numbers."
                               (< 2000 (count s)) (str (subs s 0 1997) "...")
                               :else s))}))

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
  [patterns name env id]
  (d/q '[:find (pull ?e ?patterns) .
         :in $ ?patterns ?name ?env ?id
         :where [?e :tag/name ?name]
                [?e ?env ?id]] @db/conn patterns name env id))

(defn tagq-args [q interaction]
  (q (if (:guild-id interaction)
       :tag/guild
       :tag/user) (or (:guild-id interaction) (:id (:user interaction)))))

(defn tagq
  [name interaction patterns]
  (tagq-args (partial tagq* patterns name) interaction))

(defn tag-autocomplete
  "Handles tag autocompletion searching by name."
  [conn interaction name]
  (respond conn interaction 8
           :data {:choices (for [name (tagq-args (partial d/q
                                                          '[:find [?tag-name ...]
                                                            :in $ ?name ?env ?id
                                                            :where [?e :tag/name ?tag-name]
                                                                   [?e ?env ?id]
                                                                   [(clojure.string/lower-case ?tag-name) ?lower-tag-name]
                                                                   [(clojure.string/includes? ?lower-tag-name ?name)]]
                                                          @db/conn (str/lower-case name))
                                                 interaction)]
                             {:name name
                              :value name})}))

(defn tag-get
  "Subcommand for retrieving a tag by name."
  [conn interaction]
  (let [name (:name (:options (:get (:options (:data interaction)))))]
    (if (:focused name)
      (tag-autocomplete conn interaction (:value name))
      (respond conn interaction (:channel-message-with-source interaction-response-types)
               :data (if-let [tag (tagq (:value name) interaction [:tag/name :tag/content])]
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
               :data {:content (if (tagq name interaction [])
                                 "Tag already exists."
                                 (do (d/transact! db/conn [(let [gid (:guild-id interaction)
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
               :data {:content (if-let [tag (tagq (:value name) interaction [:db/id])]
                                 (do (d/transact! db/conn [[:db.fn/retractEntity (:db/id tag)]])
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
                                            (select-keys [:default-permission :doc :options :required :type])
                                            (rename-keys {:doc :description})
                                            (assoc :fn v)))
                   m))) {} (ns-publics *ns*)))

(def discord-commands
  "A vector of command maps conforming to the [Bulk Overwrite Global Application Commands](https://discord.com/developers/docs/interactions/application-commands#bulk-overwrite-global-application-commands)
   endpoint."
  (reduce-kv #(conj %1 (-> %3
                           (assoc :name %2)
                           (dissoc :fn))) [] commands))
