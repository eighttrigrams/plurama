(ns plurama.app.views
  (:require [hiccup2.core :as h]))

(def ^:private style "
body{font-family:system-ui,sans-serif;max-width:720px;margin:2em auto;padding:0 1em;color:#222}
h1{font-weight:300;margin-bottom:.2em}
h2{font-weight:400;margin-top:2em;border-bottom:1px solid #eee;padding-bottom:.2em}
nav{margin-bottom:2em;font-size:.9em;color:#666}
nav a{margin-right:1em;color:#06c;text-decoration:none}
nav a:hover{text-decoration:underline}
ul{list-style:none;padding:0}
li{padding:.5em 0;border-bottom:1px solid #eee;display:flex;justify-content:space-between;align-items:center}
li a{color:#06c;text-decoration:none}
li a:hover{text-decoration:underline}
form.inline{display:inline}
form.row{display:flex;gap:.5em;flex-wrap:wrap;margin:1em 0}
form.row input,form.row select{padding:.4em .6em;border:1px solid #ccc;border-radius:3px;font-size:.95em}
form.row button,button{padding:.4em .8em;background:#06c;color:#fff;border:0;border-radius:3px;cursor:pointer;font-size:.95em}
form.row button:hover,button:hover{background:#048}
button.danger{background:#c33}button.danger:hover{background:#a22}
.muted{color:#888;font-size:.85em}
.error{color:#c33;padding:.6em;background:#fee;border-radius:3px;margin:1em 0}
code{background:#f4f4f4;padding:.1em .3em;border-radius:3px;font-size:.9em}
table{width:100%;border-collapse:collapse;margin:1em 0}
th,td{text-align:left;padding:.5em;border-bottom:1px solid #eee}
")

(defn- nav [logged-in?]
  [:nav
   [:a {:href "/"} "plurama"]
   (if logged-in?
     (list [:a {:href "/admin/users"} "users"]
           [:a {:href "/logout"} "log out"])
     [:a {:href "/login"} "log in"])])

(defn- layout [{:keys [title logged-in?]} & body]
  (str "<!DOCTYPE html>"
       (h/html
         [:html
          [:head
           [:meta {:charset "utf-8"}]
           [:title (or title "plurama")]
           [:style (h/raw style)]]
          [:body
           (nav logged-in?)
           body]])))

(defn landing-page [{:keys [umbrella logged-in?]}]
  (layout {:title "plurama" :logged-in? logged-in?}
    [:h1 "plurama"]
    [:p "An umbrella JVM hosting multiple apps, routed by Host header."]
    [:h2 "Hosted apps"]
    [:ul
     (for [[host k] (sort-by first (:hosts umbrella))
           :when (not= k :plurama)]
       [:li
        [:a {:href (str "https://" host "/")} host]
        [:code (name k)]])]))

(defn login-page [{:keys [error]}]
  (layout {:title "log in — plurama"}
    [:h1 "Log in"]
    (when error [:div.error error])
    [:form.row {:method "post" :action "/login"}
     [:input {:type "password" :name "password" :placeholder "admin password" :autofocus true}]
     [:button {:type "submit"} "Log in"]]))

(defn users-page [{:keys [users error]}]
  (layout {:title "users — plurama" :logged-in? true}
    [:h1 "Users"]
    (when error [:div.error error])
    [:form.row {:method "post" :action "/admin/users"}
     [:input {:type "text" :name "name" :placeholder "new user name" :required true}]
     [:button {:type "submit"} "Add user"]]
    (if (seq users)
      [:ul
       (for [u users]
         [:li
          [:span
           [:a {:href (str "/admin/users/" (:id u))} (:name u)]
           " "
           [:span.muted (str "(" (:credential_count u) " credential"
                             (if (= 1 (:credential_count u)) "" "s")
                             ")")]]
          [:form.inline {:method "post"
                         :action (str "/admin/users/" (:id u) "/delete")
                         :onsubmit (str "return confirm('Delete user " (:name u) "?')")}
           [:button.danger {:type "submit"} "Delete"]]])]
      [:p.muted "No users yet."])))

(defn user-page [{:keys [user credentials telegram-link error]}]
  (layout {:title (str (:name user) " — plurama") :logged-in? true}
    [:h1 (:name user)]
    [:p.muted "Created " (:created_at user)]
    (when error [:div.error error])
    [:h2 "Telegram"]
    [:p.muted "Map this plurama user to a Telegram account. The bot uses the "
     "Telegram " [:code "from.id"] " on each incoming message to look up which user "
     "it's acting on behalf of."]
    (when telegram-link
      [:p "Currently linked: " [:code (:telegram_user_id telegram-link)]
       (when (:display_name telegram-link)
         (list " — " (:display_name telegram-link)))
       " " [:span.muted (str "(since " (:created_at telegram-link) ")")]])
    [:form.row {:method "post" :action (str "/admin/users/" (:id user) "/telegram")}
     [:input {:type "text" :name "telegram_user_id"
              :placeholder "telegram user id (e.g. 361811399)"
              :value (or (:telegram_user_id telegram-link) "")
              :required true}]
     [:input {:type "text" :name "display_name"
              :placeholder "display name (optional)"
              :value (or (:display_name telegram-link) "")}]
     [:button {:type "submit"} (if telegram-link "Update" "Link")]
     (when telegram-link
       [:form.inline {:method "post"
                      :action (str "/admin/users/" (:id user) "/telegram/delete")
                      :onsubmit "return confirm('Remove Telegram link?')"}
        [:button.danger {:type "submit"} "Unlink"]])]
    [:h2 "Password"]
    [:p.muted "Used together with the user name (" [:code (:name user)]
     ") to log in to other apps."]
    [:p "Current: "
     (if (:password user)
       [:code (:password user)]
       [:span.muted "(not set)"])]
    [:form.row {:method "post" :action (str "/admin/users/" (:id user) "/password")}
     [:input {:type "text" :name "password"
              :placeholder "new password" :required true
              :value (or (:password user) "")}]
     [:button {:type "submit"} "Set password"]]
    [:h2 "Per-app credentials"]
    [:form.row {:method "post" :action (str "/admin/users/" (:id user) "/credentials")}
     [:input {:type "text" :name "app" :placeholder "app (e.g. tracker)" :required true}]
     [:input {:type "text" :name "username" :placeholder "username" :required true}]
     [:input {:type "text" :name "password" :placeholder "password" :required true}]
     [:button {:type "submit"} "Add credential"]]
    (if (seq credentials)
      [:table
       [:thead [:tr [:th "App"] [:th "Username"] [:th "Password"] [:th]]]
       [:tbody
        (for [c credentials]
          [:tr
           [:td [:code (:app c)]]
           [:td (:username c)]
           [:td [:code (:password c)]]
           [:td
            [:form.inline {:method "post"
                           :action (str "/admin/users/" (:id user)
                                        "/credentials/" (:id c) "/delete")
                           :onsubmit "return confirm('Delete this credential?')"}
             [:button.danger {:type "submit"} "Delete"]]]])]]
      [:p.muted "No credentials yet."])))
