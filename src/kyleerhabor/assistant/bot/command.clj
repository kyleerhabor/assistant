(ns kyleerhabor.assistant.bot.command
  (:require
   [clojure.core.async :refer [<! go]]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [kyleerhabor.assistant.bot.util :refer [avatar-url user]]
   [kyleerhabor.assistant.config :refer [config]]
   [discljord.messaging :as msg]
   [discljord.messaging.specs :refer [command-option-types interaction-response-types]]))

;; From 16 to 4096
(def image-sizes (map #(long (Math/pow 2 %)) (range 4 13)))

(def max-image-size (last image-sizes))

(defn option [opt name]
  ;; The :options could be converted to a map prior and accessed as a get, but since most interactions will only list a
  ;; few options, it would likely be an array map, killing the performance benefits and only leaving the nice API look.
  (first (filter #(= name (:name %)) (:options opt))))

(defn avatar [inter]
  (let [data (:data inter)
        user (if-let [opt (option data "user")]
               (get (:users (:resolved data)) (:value opt))
               (user inter))
        size (or (:value (option data "size")) max-image-size)
        attach? (:value (option data "attach"))
        url (avatar-url user size)]
    (if attach?
      [[:create-interaction-response {:id (:id inter)
                                      :token (:token inter)
                                      :type (:deferred-channel-message-with-source interaction-response-types)
                                      :handler (fn [_]
                                                 (let [path (.getPath (io/as-url url))
                                                       filename (subs path (inc (str/last-index-of path \/)))]
                                                   ;; This opens a stream for the URL, which could be an expensive
                                                   ;; operation. As Assistant is private, this isn't much of a problem
                                                   ;; in terms of resource use, but is something to consider.
                                                   ;;
                                                   ;; TODO: Add error handling for when attaching the image would be too
                                                   ;; large. Tried with a followup in a followup, but that didn't work. :(
                                                   [[:create-followup-message {:app-id (:application-id inter)
                                                                               :token (:token inter)
                                                                               :opts {:stream {:content url
                                                                                               :filename filename}}}]]))}]]
      [[:create-interaction-response {:id (:id inter)
                                      :token (:token inter)
                                      :type (:channel-message-with-source interaction-response-types)
                                      :opts {:data {:content url}}}]])))

(def commands {:avatar {:handler avatar}})

(defn handler
  ([inter] (handler commands inter))
  ([cmds inter]
   (if-let [name (case (:name (:data inter))
                   "avatar" :avatar
                   nil)]
     (:handler (name cmds)))))

;; Gross, but works. Please redo. This naive merging is really a bad idea for command-specific configuration (e.g.
;; setting a timeout duration for a purge/delete command) since it'll be included.
(def discord-commands [(merge
                         {:name "avatar"}
                         (:avatar (::commands config))
                         {:options [(merge
                                      {:type (:user command-option-types)
                                       :name "user"}
                                      (:user (:options (:avatar (::commands config)))))
                                    (merge
                                      {:type (:integer command-option-types)
                                       :name "size"
                                       :choices (map #(zipmap [:name :value] [(str %) %]) image-sizes)}
                                      (:size (:options (:avatar (::commands config)))))
                                    (merge
                                      {:type (:boolean command-option-types)
                                       :name "attach"}
                                      (:attach (:options (:avatar (::commands config)))))]})])

(defn upload
  ([conn] (upload conn discord-commands))
  ([conn cmds]
   (go
     (let [{:keys [id]} (<! (msg/get-current-application-information! conn))]
       (<! (msg/bulk-overwrite-global-application-commands! conn id cmds))))))
