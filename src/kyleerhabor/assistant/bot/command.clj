(ns kyleerhabor.assistant.bot.command
  (:require
   [clojure.core.async :refer [<! go]]
   [clojure.math :as math]
   [clojure.set :refer [rename-keys]]
   [clojure.string :as str]
   [kyleerhabor.assistant.bot.cdn :as cdn]
   [kyleerhabor.assistant.bot.schema :refer [max-get-channel-messages-limit message-flags]]
   [kyleerhabor.assistant.bot.util :refer [user]]
   [kyleerhabor.assistant.config :refer [config]]
   [kyleerhabor.assistant.database :as db]
   [kyleerhabor.assistant.effect :as fx]
   [kyleerhabor.hue.schema.domain.series :as-alias series]
   [kyleerhabor.hue.schema.domain.name :as-alias name]
   [cprop.tools :refer [merge-maps]]
   [discljord.permissions :as perm]
   [discljord.cdn :as dcdn]
   [discljord.messaging :as msg]
   [discljord.messaging.specs :refer [command-option-types interaction-response-types]]
   [com.rpl.specter :as sp])
  (:import
   (java.time Instant)
   (java.time.temporal ChronoUnit)))

;; From 16 to 4096
(def image-sizes (map #(long (math/pow 2 %)) (range 4 13)))

(def max-image-size (last image-sizes))

(defn respond [inter]
  [:create-interaction-response {:id (:id inter)
                                 :token (:token inter)}])

(defn error [inter]
  (fx/update (respond inter)
    (fn [data]
      (assoc data
        :type (:channel-message-with-source interaction-response-types)
        :opts {:data {:flags (:ephemeral message-flags)}}))))

(defn animanga [{inter :interaction}]
  (let [id (:value (:query (:options (:data inter))))]
    [[:get-animanga
      {:id id
       :handler (fn [series]
                  [(fx/update (respond inter)
                     (fn [data]
                       (assoc data
                         :type (:channel-message-with-source interaction-response-types)
                         :opts {:data (if series
                                        ;; :en > :ja. Naive, but works.
                                        (let [[name & names] (sort-by ::name/language (::series/names series))]
                                          {:embeds [{:title (::name/content name)
                                                     :url (str (:url (:animanga (::commands config))) "/series/" (::name/id name))
                                                     :description (str/join "\n" (map #(str "- " (::name/content %)) names))}]}) 
                                        {:content "Not found."
                                         :flags (:ephemeral message-flags)})})))])}]]))

(defn avatar-for-user [inter]
  (let [data (:data inter)
        uid (:value (:user (:options data)))
        usr (get (:users (:resolved data)) uid)
        user (or usr (user inter))
        hash (:avatar user)
        ;; Some coupling here with the and since it's aware of the user default
        fmt (or (:value (:format (:options data))) (and hash (name (cdn/format hash))))]
    {:name hash
     :format fmt
     :path (cdn/path (:user-avatar cdn/endpoints) {:id (:id user)
                                                   :avatar hash
                                                   :format fmt})}))

(defn avatar-for-user-default [inter]
  (let [data (:data inter)
        uid (:value (:user (:options data)))
        usr (get (:users (:resolved data)) uid)
        user (or usr (user inter))
        n (cdn/disnum (parse-long (:discriminator user)))
        fmt (name cdn/default-format)]
    {:name n
     :format fmt
     :path (cdn/path (:default-user-avatar cdn/endpoints) {:number n
                                                           :format fmt})}))

(defn avatar-response [inter {:keys [name format]
                              :as image}]
  (let [options (:options (:data inter))]
    (if (and (= "gif" format) (not (dcdn/animated? name)))
      [(fx/update (error inter)
         (fn [data]
           (assoc-in data [:opts :data :content] "GIF format only supports animated avatars.")))]
      (let [size (or (:value (:size options)) max-image-size)
            url (dcdn/resize (str dcdn/base-url (:path image)) size)
            attach? (:value (:attach? options))]
        [(fx/update (respond inter)
           (fn [data]
             (if attach?
               (assoc data
                 :type (:deferred-channel-message-with-source interaction-response-types)
                 :handler (fn [_]
                            (let [filename (cdn/file name format)]
                              ;; TODO: Add error handling for when attaching the image would be too large. I tried
                              ;; with a followup in a followup, but that didn't work.
                              [[:create-followup-message {:app-id (:application-id inter)
                                                          :token (:token inter)
                                                          :opts {:stream {:content url
                                                                          :filename filename}}}]])))
               (assoc data
                 :type (:channel-message-with-source interaction-response-types)
                 :opts {:data {:content url}}))))]))))

(defn avatar [{inter :interaction}]
  ;; Quite a small implementation, but that's because it was previously refactored to support displaying banners and
  ;; server avatars for members. It turned out, however, that Discord does not include the hashes for either in the
  ;; interaction, so a request would need to be issued. I currently don't feel like going through the trouble of
  ;; implementing it, so this partial remains.
  (let [image (avatar-for-user inter)
        image (if (:name image)
                image
                (avatar-for-user-default inter))]
    (avatar-response inter image)))
    

(defn purge-result [inter n]
  ;; This practically duplicates error—maybe rename it?
  (fx/update (respond inter)
    (fn [data]
      (assoc data
        :type (:channel-message-with-source interaction-response-types)
        :opts {:data {:content (case n
                                 0 "Could not delete messages."
                                 1 "Deleted 1 message."
                                 (str "Deleted " n " messages."))
                      :flags (:ephemeral message-flags)}}))))

(defn purge [{inter :interaction}]
  ;; It's a little unsafe to parse :permissions as a long since it could be too large. Discord recommends using a big
  ;; integer. Also, the permission namespace Discljord provides isn't great, in my opinion, as it relies on one implicit
  ;; var for the permissions (e.g. if Discord creates a new permission, Discljord has to update the var, unless we're
  ;; able to use something like `alter-root-var`).
  (let [cid (:channel-id inter)
        amount (:value (:amount (:options (:data inter))))]
    [[:get-channel-messages
      {:channel-id cid
       ;; The filter may return less than the requested amount. To alleviate this burden for the user, we're going to
       ;; fetch the maximum amount of messages in a single request, run the filters, then take the requested amount.
       ;; Note that the result could still return less than desired.
       :opts {:limit max-get-channel-messages-limit}
       :handler (fn [msgs]
                  ;; Does time manipulation *really* have anything to do with my problem?
                  ;;
                  ;; It may be possible to extract the time from the interaction and use that to measure how old a
                  ;; message is, but since :get-channel-messages is asynchronous, that time could be out of sync
                  ;; again. This is most likely an uphill battle not worth fighting, so let's compromise instead.
                  ;;
                  ;; For ideas: https://github.com/DasWolke/SnowTransfer/blob/master/src/methods/Channels.ts#L420
                  (let [old (-> (Instant/now)
                              (.minus 14 ChronoUnit/DAYS)
                              ;; In case time drifts between Assistant and Discord.
                              (.plus 1 ChronoUnit/MINUTES))
                        ids (->> msgs
                              (filter #(not (:pinned %)))
                              (filter #(.isAfter (Instant/parse (:timestamp %)) old))
                              (take amount)
                              (map :id))
                        n (count ids)
                        deleted-response (fn [success?]
                                           [(purge-result inter (if success? n 0))])]
                    (case n
                      0 [(fx/update (error inter)
                           (fn [data]
                             (assoc-in data [:opts :data :content] "No messages to delete")))]
                      ;; Bulk deletes require at least two messages.
                      1 [[:delete-message
                          {:channel-id cid
                           :message-id (first ids)
                           :handler deleted-response}]]
                      [[:bulk-delete-messages
                        {:channel-id cid
                         :msg-ids ids
                         :handler deleted-response}]])))}]]))

(defn tag-create [{inter :interaction}]
  (let [name (:value (:name (:options (:data inter))))
        db @db/db
        gt-path [:guilds (:guild-id inter) :tags]]
    (if (some #(= name (:name (get-in db %))) (get-in db gt-path))
      [(fx/update (error inter)
         (fn [data]
           (assoc-in data [:opts :data :content] "Tag with name already exists")))]
      (do
        (swap! db/db
          (fn [db]
            (let [id (random-uuid)
                  t-path [:tags id]
                  title (:value (:title (:options (:data inter))))
                  desc (:value (:description (:options (:data inter))))]
              (-> db
                (assoc-in t-path {:id id
                                  :name name
                                  :title title
                                  :description desc})
                (update-in gt-path conj t-path)))))
        [(fx/update (respond inter)
           (fn [data]
             (assoc data
               :type (:channel-message-with-source interaction-response-types)
               :opts {:data {:content "Tag created."}})))]))))

(defn tag-display [{inter :interaction}]
  (let [name (:value (:name (:options (:data inter))))
        db @db/db
        guild (get (:guilds db) (:guild-id inter))]
    (if-let [tag* (first (filter #(= name (:name (get-in db %))) (:tags guild)))]
      (let [tag (get-in db tag*)]
        [(fx/update (respond inter)
           (fn [data]
             (assoc data
               :type (:channel-message-with-source interaction-response-types)
               :opts {:data {:embeds [{:title (or (:title tag) (:name tag))
                                       :description (:description tag)}]}})))])
      [(fx/update (error inter)
         (fn [data]
           (assoc-in data [:opts :data :content] "Tag not found.")))])))

(defn tag-delete [{inter :interaction}]
  (let [name (:value (:name (:options (:data inter))))
        db @db/db
        gt-path [:guilds (:guild-id inter) :tags]]
    (if-let [t (first (filter #(= name (:name (get-in db %))) (get-in db gt-path)))]
      (let [id (:id (get-in db t))]
        (swap! db/db
          (fn [db]
            (-> db
              (update :tags dissoc id)
              (update-in gt-path (fn [ts]
                                   (remove (fn [[_ tid]]
                                             (= tid id)) ts))))))
        [(fx/update (respond inter)
           (fn [data]
             (assoc data
               :type (:channel-message-with-source interaction-response-types)
               :opts {:data {:content "Tag deleted."}})))])
      [(fx/update (error inter)
         (fn [data]
           (assoc-in data [:opts :data :content] "Tag not found.")))])))

(defn choice [v]
  {:name (str v)
   :value v})

(def default-commands
  {:animanga {:handler animanga
              :name "animanga"
              :description "Searches for anime and manga."
              :options {:query {:name "query"
                                :type (:string command-option-types)
                                :description "The anime or manga to search for."
                                :required? true
                                :min-length 4}}}
   :avatar {:handler avatar
            :name "avatar"
            :description "Displays a user's avatar."
            :options {:user {:name "user"
                             :type (:user command-option-types)
                             :description "The user to retrieve the avatar of, defaulting to whoever ran the command."}
                      :size {:name "size"
                             :type (:integer command-option-types)
                             :description (str
                                            "The largest size to return the avatar in. Actual avatar size will be lower"
                                            " if unavailable.")
                             ;; No, I'm not going to auto-generate the keys from a mere number. The keys are names for
                             ;; programmers (a separate space). I've dealt with worse cases of auto-generated names
                             ;; (GraphQL struct generator in Swift), and it's not fun.
                             ;;
                             ;; The prefixed "s" means size. It's possible to start a keyword with a number, but it's a
                             ;; bad practice with limited support: https://clojure.org/guides/faq#keyword_number
                             :choices (update-vals {:s16 16
                                                    :s32 32
                                                    :s64 64
                                                    :s128 128
                                                    :s256 256
                                                    :s512 512
                                                    :s1024 1024
                                                    :s2048 2048
                                                    :s4096 4096} choice)}
                      :format {:name "format"
                               :type (:string command-option-types)
                               ;; Should it be "file format" or "image format" instead of just "format"?
                               :description "The format to return the avatar in, defaulting to PNG."
                               ;; See https://discord.com/developers/docs/reference#image-formatting-image-formats
                               :choices {:png {:name "PNG"
                                               :value "png"}
                                         :jpg {:name "JPEG"
                                               :value "jpg"}
                                         :webp {:name "WebP"
                                                :value "webp"}
                                         :gif {:name "GIF"
                                               :value "gif"}}}
                      :attach? {:name "attach"
                                :type (:boolean command-option-types)
                                ;; The second sentence could be improved. After what updates? Also, to users in the app,
                                ;; the first part may be confusing, since sending avatars as links appear as attachments.
                                :description (str
                                               "Whether or not to send the avatar as an attachment. Useful for retaining"
                                               " avatars after updates.")}}}
   :purge {:handler purge
           :name "purge"
           :description "Deletes messages from a channel." ; Should it be "in" instead of "from"?
           :permissions [:manage-messages]
           :dms? false
           :options {:amount {:name "amount"
                              :type (:integer command-option-types)
                              :description "The largest number of messages to delete. Actual amount may be lower."
                              :required? true
                              :min-value 1
                              :max-value 100}}}
   :tag {:name "tag"
         :description "Response facilities."
         :options {:create {:handler tag-create
                            :name "create"
                            :type (:sub-command command-option-types)
                            :description "Creates a tag."
                            :options {:name {:name "name"
                                             :type (:string command-option-types)
                                             :description "The name for the tag."
                                             :required? true}
                                      :title {:name "title"
                                              :type (:string command-option-types)
                                              :description "The text to use for the title, defauling to the provided name."}
                                      :description {:name "description"
                                                    :type (:string command-option-types)
                                                    :description "The text to use for the description."}}}
                   ;; Maybe provide autocompletion?
                   :display {:handler tag-display
                             :name "display"
                             :type (:sub-command command-option-types)
                             :description "Displays a tag."
                             :options {:name {:name "name"
                                              :type (:string command-option-types)
                                              :description "The name of the tag."
                                              :required? true}}}
                   :delete {:handler tag-delete
                            :name "delete"
                            :type (:sub-command command-option-types)
                            :description "Deletes a tag."
                            :options {:name {:name "name"
                                             :type (:string command-option-types)
                                             :description "The name of the tag."
                                             :required? true}}}}}})

(def commands (merge-maps default-commands (::commands config)))

(defn commands-by-name [cmds]
  (reduce
    (fn [cmds [id cmd]]
      (let [cmd (update cmd :options
                  (fn index [opts]
                    (reduce
                      (fn [opts [id opt]]
                        (let [;; Less concise than using update, but won't leave empty maps everywhere.
                              opt (sp/multi-transform (sp/multi-path
                                                        [(sp/must :options) (sp/terminal index)]
                                                        [(sp/must :choices) (sp/terminal index)])
                                    opt)]
                          (assoc opts (:name opt) (assoc opt :id id)))) {} opts)))]
        (assoc cmds (:name cmd) (assoc cmd :id id))))
    {}
    cmds))

(def commands-named (commands-by-name commands))

(def sub? (set (map command-option-types [:sub-command :sub-command-group])))

(defn router [inter reg] ; Note that reg is a name resolver.
  (loop [opt (:data inter)
         reg reg
         path []]
    (let [name (:name opt)
          res {:path path
               :option opt
               :registry reg}]
      (if-let [item (get reg name)]
        (let [path (conj path (:id item))
              reg (:options item)
              ;; Note the difference in scoping.
              res (assoc res
                    :path path
                    :registry reg)]
          (if-let [nopt (first (:options opt))]
            (if (sub? (:type nopt))
              (recur nopt reg path)
              res)
            res))
        res))))

(defn route [router reg] ; Note that :reg is stored in router, while the reg param is a "true" commands map.
  {:registry (get-in reg (interpose :options (:path router)))
   :option (persistent! (reduce (fn [m {:keys [name]
                                        :as opt}]
                                  (assoc! m (:id (get (:registry router) name)) opt))
                          (transient {}) (:options (:option router))))})

(declare discord-option)

(defn apply-discord-options [opt desc]
  (sp/transform (sp/must :options)
    (fn [opts]
      (map
        (fn [{:keys [id]
              :as odesc}]
          (discord-option (id opts) odesc)) (:options desc))) opt))

(defn discord-option [opt desc]
  (let [opt (-> opt
              (select-keys [:type :name :description :required? :min-value :max-value :min-length :max-length :choices
                            :options])
              (rename-keys {:required? :required
                            :min-value :min_value
                            :max-value :max_value
                            :min-length :min_length
                            :max-length :max_length}))
        opt (sp/transform (sp/must :choices)
              (fn [choices]
                (map
                  (fn [{:keys [id]}]
                    (id choices))
                  (:choices desc)))
              opt)]
    (apply-discord-options opt desc)))

(defn discord-command
  "Converts a command into a representation (map) compatible with Discord (for upload)."
  [cmd desc]
  (let [cmd (-> cmd
              (select-keys [:name :description :permissions :dms? :options])
              (rename-keys {:permissions :default_member_permissions
                            :dms? :dm_permission}))
        cmd (sp/transform (sp/must :default_member_permissions) perm/permission-int cmd)]
    (apply-discord-options cmd desc)))

(def discord-commands (map #(discord-command ((:id %) commands) %)
                         [{:id :avatar
                           :options [{:id :user}
                                     {:id :size
                                      :choices [{:id :s16}
                                                {:id :s32}
                                                {:id :s64}
                                                {:id :s128}
                                                {:id :s256}
                                                {:id :s512}
                                                {:id :s1024}
                                                {:id :s2048}
                                                {:id :s4096}]}
                                     {:id :format
                                      :choices [{:id :png}
                                                {:id :jpg}
                                                {:id :webp}
                                                {:id :gif}]}
                                     {:id :attach?}]}
                          {:id :purge
                           :options [{:id :amount}]}
                          {:id :tag
                           :options [{:id :create
                                      :options [{:id :name}
                                                {:id :title}
                                                {:id :description}]}
                                     {:id :display
                                      :options [{:id :name}]}
                                     {:id :delete
                                      :options [{:id :name}]}]}]))

(defn upload
  ([conn] (upload conn discord-commands))
  ([conn cmds]
   (go
     (let [{:keys [id]} (<! (msg/get-current-application-information! conn))]
       (<! (msg/bulk-overwrite-global-application-commands! conn id cmds))))))
