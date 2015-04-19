(ns bunshin.backend)


(defprotocol BunshinStorageBackend
  (pipeline [this server-conf thunk-fn] "A function to run thunk of db queries")

  (get-id-xs [this key] "Get list of ids for a given key")
  (get [this key] "Get value for a given key")

  (set [this val-key val id-key id] "Store value and value against keys")

  (prune-ids [this id-key] "Delete all ids but the largest id for given id key. ")
  (del [this keys] "Delete keys"))
