(ns birdwatch.persistence.persistence
  (:gen-class)
  (:require
    [clojure.core.match :refer [match]]
    [matthiasn.systems-toolbox.component :as comp]
    [clojurewerkz.elastisch.rest :as esr]
    [clojurewerkz.elastisch.rest.document :as esd]
    [clojurewerkz.elastisch.rest.response :as esrsp]))

(defn mk-state
  "Returns function for making state while using provided configuration."
  [conf]
  (fn [put-fn]
    (let [es-address (:es-address conf)
          conn (esr/connect es-address)]
      (println "ElasticSearch connection started to" es-address)
      (put-fn [:log/info (str "ElasticSearch connection started to " es-address)])
      (atom {:conf conf :conn conn :state (atom {})}))))

(defn- strip-tweet
  "take only actually needed fields from tweet"
  [t]
  (let [u (:user t)]
    {:id_str        (:id_str t) :id (:id t) :text (:text t) :created_at (:created_at t)
     :retweet_count (:retweet_count t) :favorite_count (:favorite_count t) :entities (:entities t)
     :user          {:followers_count (:followers_count u) :name (:name u) :profile_image_url (:profile_image_url u)
                     :screen_name     (:screen_name u)}}))

(defn strip-source
  "get tweet stripped down to necessary fields"
  [val]
  (let [s (:_source val) t (strip-tweet s) rt (:retweeted_status s)]
    (if rt (assoc t :retweeted_status (strip-tweet rt)) t)))

(defn get-source
  "get vector with :_source of each ElasticSearch result"
  [coll]
  (map strip-source coll))

(defn mk-query
  "Run a query on previous matching tweets."
  [{:keys [query n from]} conf conn]
  (let [search (esd/search conn (:es-index conf) "tweet" :query query :size n :from from :sort {:id :desc})
        hits (esrsp/hits-from search)
        source (get-source hits)
        res (vec source)]
    res))

(defn es-query
  "Handler function for previous tweets. Uses put-fn for returning results."
  [{:keys [cmp-state put-fn msg-payload]}]
  (let [conf (:conf @cmp-state)
        conn (:conn @cmp-state)]
    (put-fn [:tweet/prev-chunk {:uid (:uid msg-payload) :result (mk-query msg-payload conf conn)}])))

(defn es-mt-query
  "Handler function for missing tweets. Uses put-fn for returning results."
  [{:keys [cmp-state put-fn msg-payload]}]
  (let [conf (:conf @cmp-state)
        conn (:conn @cmp-state)
        res (esd/get conn (:es-index conf) "tweet" (:id_str msg-payload))]
    (when-not res (put-fn [:log/info [:persistence-cmp "missing tweet:" (:id_str msg-payload)]]))
    (put-fn [:tweet/missing-tweet {:tweet (strip-source res) :uid (:uid msg-payload)}])))

(defn total-tweets-indexed
  [{:keys [cmp-state put-fn]}]
  (let [conf (:conf @cmp-state)
        conn (:conn @cmp-state)
        cnt (esd/count conn (:es-index conf) "tweet")]
    (put-fn [:stats/total-tweet-count (format "%,15d" (:count cnt))])))

(defn component
  "Create component for retrieving documents from ElasticSearch"
  [cmp-id conf]
  (comp/make-component {:cmp-id      cmp-id
                        :state-fn    (mk-state conf)
                        :handler-map {:schedule/count-indexed total-tweets-indexed
                                      :cmd/query              es-query
                                      :cmd/missing            es-mt-query}}))
