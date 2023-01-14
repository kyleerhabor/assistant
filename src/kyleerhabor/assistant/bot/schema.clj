;; Rename to domain?
(ns kyleerhabor.assistant.bot.schema)

(def discord-json-error-codes {:request-entity-too-large 40005})

(def message-flags {:ephemeral (bit-shift-left 1 6)})
