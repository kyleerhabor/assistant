(ns assistant.utils)

(defn truncate
  "Limits string `s` to `lim` number of characters, appending `trunc` to indicate truncation."
  ([s lim] (truncate s lim "..."))
  ([s lim trunc]
   (if (< lim (count s))
     (str (subs s 0 (- lim (count trunc))) trunc)
     s)))

(defmacro ignore-ex
  "Evaluates `body`, returning the value of the last expression or `nil` if an exception is caught."
  [& body]
  `(try ~@body
     (catch Exception _#)))

(defn rpartial
  "Like `partial`, but applies the args called with `f` before the partial args (`pargs`)."
  [f & pargs] ; p[artial] args
  (fn [& rargs] ; r[eal] args
    (apply f (concat rargs pargs))))

(defn split-keys
  "Splits map `m` into two maps containing keys from (first) and not from (second) `ks`."
  [m ks]
  [(select-keys m ks)
   (apply dissoc m ks)])