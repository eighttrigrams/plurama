(ns plurama.app.agent.tools
  (:require [plurama.app.agent.app-client :as app-client]))

(defn build-tool-specs
  "Build the LLM-facing tool specs for the apps this user has access to.
  `app-names` is a sequence of strings (e.g. [\"tracker\" \"personalist\"]).
  Returns a single `app_request` tool whose :app param enum is
  constrained to the apps the user is credentialed for."
  [app-names]
  [{:name "app_request"
    :description (str "Make an authenticated HTTP request to one of the configured "
                      "apps' /api/* surfaces. Use the `app` parameter to pick which "
                      "downstream app to call. Returns the raw HTTP status and "
                      "response body. Consult the per-app guidance in the system "
                      "prompt for available endpoints and request shapes.")
    :input_schema {:type "object"
                   :properties {:app    {:type "string"
                                         :enum (vec app-names)
                                         :description "Which configured app to call."}
                                :method {:type "string"
                                         :enum ["GET" "POST" "PUT" "DELETE"]
                                         :description "HTTP method"}
                                :path   {:type "string"
                                         :description "Path starting with /api/, e.g. /api/today-board"}
                                :body   {:type "object"
                                         :description "Optional JSON body for POST/PUT"}}
                   :required ["app" "method" "path"]}}])

(defn run
  "Dispatch a tool call. `app-ctxs` is a map keyed by app name (string)
  → {:base-url :username :password}. Returns the result string the LLM
  will see."
  [app-ctxs {:keys [name input]}]
  (case name
    "app_request"
    (let [{:keys [app method path body]} input
          ctx (get app-ctxs app)]
      (if ctx
        (let [{:keys [status body]} (app-client/request ctx method path body)]
          (str "HTTP " status "\n" body))
        (str "Error: no credentials configured for app: " app)))

    (str "Unknown tool: " name)))
