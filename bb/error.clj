(ns error)


(defn error!

  ([message]
   (throw (ex-info message {})))

  ([message data]
   (throw (ex-info message data)))

  ([message data cause]
   (throw (ex-info message data cause))))