(ns plurama.app.agent.app-client
  "Generic HTTPS client for any downstream app's /api/* surface.
  Callers pass an `app-ctx` describing the base URL and the per-user
  machine credentials. Bearer tokens are cached in-process per
  (base-url, username), with a conservative TTL and refresh-on-401
  behaviour. Assumes the downstream app exposes
  POST /api/auth/login → {:token ...} and accepts
  Authorization: Bearer <token> on subsequent /api/* calls."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration Instant]))

(def ^:private client (HttpClient/newHttpClient))

;; In-process token cache keyed by [base-url username].
;; Value: {:token "..." :expires-at <Instant>}
(defonce ^:private *token-cache (atom {}))

;; Conservative TTL — tracker tokens are typically valid for hours, but
;; we refresh well before any plausible expiry to avoid mid-call 401s.
(def ^:private token-ttl-seconds 600)

(defn- login!
  "Hit /api/auth/login with the given creds and return the bearer token.
  Throws if login fails."
  [base-url username password]
  (let [request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str base-url "/api/auth/login")))
                    (.timeout (Duration/ofSeconds 30))
                    (.header "Content-Type" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString
                            (json/write-str {:username username
                                             :password password})))
                    (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofString))
        token (some-> (.body response) (json/read-str :key-fn keyword) :token)]
    (when-not token
      (throw (ex-info "Tracker login failed"
                      {:status (.statusCode response)
                       :body (.body response)})))
    token))

(defn- get-token [{:keys [base-url username password]}]
  (let [k [base-url username]
        now (Instant/now)
        cached (get @*token-cache k)]
    (if (and cached (.isAfter (:expires-at cached) now))
      (:token cached)
      (let [token (login! base-url username password)
            entry {:token token
                   :expires-at (.plusSeconds now token-ttl-seconds)}]
        (swap! *token-cache assoc k entry)
        token))))

(defn- invalidate-token! [{:keys [base-url username]}]
  (swap! *token-cache dissoc [base-url username]))

(defn- send-with-token [app-ctx token method path body]
  (let [{:keys [base-url]} app-ctx
        body-json (when body (json/write-str body))
        body-publisher (if body-json
                         (HttpRequest$BodyPublishers/ofString body-json)
                         (HttpRequest$BodyPublishers/noBody))
        base (-> (HttpRequest/newBuilder)
                 (.uri (URI/create (str base-url path)))
                 (.timeout (Duration/ofSeconds 60))
                 (.header "Authorization" (str "Bearer " token)))
        with-ct (if body-json
                  (.header base "Content-Type" "application/json")
                  base)
        built (case (str/upper-case method)
                "GET"    (.build (.GET with-ct))
                "POST"   (.build (.POST with-ct body-publisher))
                "PUT"    (.build (.PUT with-ct body-publisher))
                "DELETE" (.build (.DELETE with-ct))
                (throw (ex-info (str "Unsupported method: " method) {:method method})))
        response (.send client built (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response) :body (.body response)}))

(defn request
  "Make an authenticated request to a downstream app. `app-ctx` is
  {:base-url :username :password}. Returns {:status :body}. Refreshes
  the token once on 401."
  [app-ctx method path body]
  (let [token (get-token app-ctx)
        resp  (send-with-token app-ctx token method path body)]
    (if (= 401 (:status resp))
      (do (invalidate-token! app-ctx)
          (send-with-token app-ctx (get-token app-ctx) method path body))
      resp)))
