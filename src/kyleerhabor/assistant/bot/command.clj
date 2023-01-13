(ns kyleerhabor.assistant.bot.command
  (:require
   [clojure.core.async :refer [<! go]]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [kyleerhabor.assistant.bot.util :refer [avatar-url user]]
   [kyleerhabor.assistant.config :refer [config]]
   [discljord.messaging :as msg]
   [discljord.messaging.specs :refer [command-option-types interaction-response-types]]
   [tilakone.core :as tk]))

;; From 16 to 4096
(def image-sizes (map #(long (Math/pow 2 %)) (range 4 13)))

(def max-image-size (last image-sizes))

(def interaction {::tk/states [{::tk/name :init
                                ::tk/transitions [{::tk/on :respond
                                                   ::tk/to :responded
                                                   ::tk/actions [:respond]}
                                                  {::tk/on :defer-response
                                                   ::tk/to :deferred
                                                   ::tk/actions [:defer-response]}]}
                               {::tk/name :responded}
                               {::tk/name :deferred
                                ::tk/transitions [{::tk/on :respond
                                                   ::tk/to :responded ; Maybe a :realized state?
                                                   ::tk/actions [:realize-response]}]}]
                  ::tk/state :init
                  ;; A test could easily replace this to not do requests (e.g. use stub values instead), but I wonder if
                  ;; using, say, the repository pattern (see https://www.juxt.pro/blog/abstract-clojure/ as an example)
                  ;; would be better (since it would truly decouple this namespace from "how").
                  ::tk/action! (fn [{{{app-id :application-id
                                       :keys [id token]} :interaction
                                      :keys [conn]
                                      :as process} ::tk/process
                                     ::tk/keys [action]
                                     :as fsm}]
                                 (case action
                                   (:respond :defer-response)
                                   (assoc-in fsm [::tk/process :response] (msg/create-interaction-response! conn id token
                                                                            (:type (:response process))
                                                                            (:data (:response process))))
                                   :realize-response
                                   (assoc-in fsm [::tk/process :response] (msg/create-followup-message! conn app-id token
                                                                            (:data (:response process))))))})

(defn interact
  ([conn inter] (interact interaction conn inter))
  ([fsm conn inter]
   (assoc fsm
     :conn conn
     :interaction inter)))

(defn respond [fsm type data]
  (tk/apply-signal (assoc fsm :response {:type type
                                         :data data}) :respond))

(defn defer [fsm type data]
  (tk/apply-signal (assoc fsm :response {:type type
                                         :data data}) :defer-response))

(defn realize [fsm data]
  (tk/apply-signal (assoc fsm :response {:data data}) :respond))

(defn option [opt name]
  ;; The :options could be converted to a map prior and accessed as a get, but since most interactions will only list a
  ;; few options, it would likely be an array map, killing the performance benefits and only leaving the nice API look.
  (first (filter #(= name (:name %)) (:options opt))))

(defn avatar [fsm]
  (let [inter (:interaction fsm)
        data (:data inter)
        user (if-let [opt (option data "user")]
               (get (:users (:resolved data)) (:value opt))
               (user inter))
        size (or (:value (option data "size")) max-image-size)
        attach? (:value (option data "attach"))
        url (avatar-url user size)
        ;; The URL and path operations probably need to happen in the same operation (rather than creating a URL then
        ;; ripping out the path and parsing it later)
        path (.getPath (io/as-url url))]
    (if attach?
      (go
        ;; Uploading a user avatar (especially a gif) could take a while, so we're using defer.
        (let [fsm (defer fsm (:deferred-channel-message-with-source interaction-response-types) nil)]
          (<! (:response fsm)) ; Don't know if it's safe to send a followup response before a defer.
          ;; This opens a stream for the URL, which could be an expensive operation. As Assistant is private, this isn't
          ;; much of a problem in terms of resource use, but is something to consider.
          ;;
          ;; TODO: Add error handling for when attaching the image would be too large.
          (with-open [stream (io/input-stream url)]
            ;; Make sure the responder completes before the stream closes.
            (<! (:response (realize fsm {:stream {:content stream
                                                  :filename (subs path (inc (str/last-index-of path \/)))}}))))))
      (respond fsm (:channel-message-with-source interaction-response-types) {:data {:content url}}))))

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
