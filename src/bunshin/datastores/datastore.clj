(ns bunshin.datastores.datastore)


(defprotocol BunshinDataStorage
  (get-id-xs [this server-conf key] "Get list of ids for a given key")
  (get [this server-conf key] "Get value for a given key")

  (set [this server-conf val-key val id-key id] "Store value and value against keys")

  (prune-ids [this server-conf id-key] "Delete all ids but the largest id for given id key. ")
  (del [this server-conf keys] "Delete keys"))
