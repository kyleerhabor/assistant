(ns assistant.events
  (:require
    [clojure.core.async :refer [<! go]]
    [assistant.commands :refer [discord-commands commands]]
    [assistant.utils :refer [ignore-ex]]
    [com.brunobonacci.mulog :as u]
    [discljord.messaging :refer [bulk-overwrite-global-application-commands!]]))

(defn options->map
  "Recursively transforms `m` to convert `:options` keys from vectors of maps to just maps."
  [m]
  (if (contains? m :options)
    (update m :options (partial reduce #(assoc %1 (keyword (:name %2)) (options->map %2)) {}))
    m))

(defmulti handler
  "Handler for Discord API events."
  (fn [_ type _]
    (u/log ::event :type type)
    type))

(defmethod handler :interaction-create
  [conn _ interaction]
  (if (#{2 3 4} (:type interaction))
    ;; It may look like this (or ...) could be shortened to get :name once one of the two have been resolved, but do not
    ;; be fooled. The interaction data map won't have a :name key for components. The second argument could be evaluated
    ;; first, but I doubt message commands are more common than interaction commands (order of importance). Hence, :name
    ;; is checked separately.
    (if-let [name (or (:name (:data interaction))
                      (:name (:interaction (:message interaction))))]
      (ignore-ex (u/trace ::interaction-run [:name name]
                   ((:fn ((keyword name) commands)) conn (update interaction :data options->map)))))))

(defmethod handler :ready
  [conn _ data]
  (let [appid (:id (:user data))]
    (u/log ::ready :id appid :shard (:shard data))
    (go
      (u/log ::global-application-commands-set
        :commands (<! (bulk-overwrite-global-application-commands! conn appid discord-commands)))
      ))
  #_(if-let [gid (:bot/guild-id config)]
    (u/log ::guild-commands-set
      ;; TODO: Only overwrite commands registered for guilds.
      :commands @(bulk-overwrite-guild-application-commands! msg-ch id gid discord-commands)
      :guild gid)))

(defmethod handler :default
  [_ _ _])
