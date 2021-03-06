(ns google-webmaster-tools-bulk-url-removal.content-script.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :refer [<! >! put! chan] :as async]
            [hipo.core :as hipo]
            [dommy.core :refer-macros [sel sel1] :as dommy]
            [testdouble.cljs.csv :as csv]
            ;; [cognitect.transit :as t]
            [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.protocols.chrome-port :refer [post-message!]]
            [chromex.ext.runtime :as runtime :refer-macros [connect]]
            [google-webmaster-tools-bulk-url-removal.content-script.common :as common]
            [google-webmaster-tools-bulk-url-removal.background.storage :refer [clear-victims! print-victims update-storage
                                                                                current-removal-attempt get-bad-victims]]
            [cljs-time.core :as t]
            [cljs-time.coerce :as tc]
            [cemerick.url :refer [url]]
            [domina :refer [single-node nodes]]
            [domina.xpath :refer [xpath]]
            [domina.events :refer [dispatch!]]
            ))

;; default to Temporarily remove and Remove this URL only
(defn exec-new-removal-request
  "url-method: :remove-url vs :clear-cached
  url-type: :url-only vs :prefix
  Possible return value in a channel
  1. :not-in-property
  2. :duplicate-request
  3. :malform-url
  4. :success
  "
  [url url-method url-type]
  (let [ch (chan)
        url-type-str (cond (= url-type "prefix") "Remove all URLs with this prefix"
                           (= url-type "url-only") "Remove this URL only"
                           )]
    (go
      (cond (and (not= url-method "remove-url") (not= url-method "clear-cached"))
            (>! ch :erroneous-url-method)
            (and (not= url-type "url-only") (not= url-type "prefix"))
            (>! ch :erroneous-url-type)
            :else
            (do (.click (single-node (xpath "//span[contains(text(), 'New Request')]")))

                (<! (async/timeout 700)) ;; wait for the modal dialog to show
                ;; Who cares? Click on all the radiobuttons
                (doseq [n (nodes (xpath (str "//label[contains(text(), '" url-type-str "')]/div")))]
                  (.click n))

                (doseq [n (nodes (xpath "//input[@placeholder='Enter URL']"))]
                  (do
                    (.click n)
                    (domina/set-value! n url)))

                ;; NOTE: Need to click one of the tabs to get next to show
                (cond (= url-method "remove-url")
                      (do
                        (.click (single-node (xpath "//span[contains(text(), 'Clear cached URL')]")))
                        (<! (async/timeout 700))
                        (.click (single-node (xpath "//span[contains(text(), 'Temporarily remove URL')]"))))
                      (= url-method "clear-cached")
                      (.click (single-node (xpath "//span[contains(text(), 'Clear cached URL')]")))
                      ;; :else
                      ;; trigger skip-error
                      )


                (<! (async/timeout 700))
                (.click (single-node (xpath "//span[contains(text(), 'Next')]")))
                (<! (async/timeout 1400))

                ;; Check for "URL not in property"
                (if-let [not-in-properity-node (single-node (xpath "//div[contains(text(), 'URL not in property')]"))]
                  ;; Oops, not in the right domain
                  (do
                    (.click (single-node (xpath "//span[contains(text(), 'Close')]")))
                    (<! (async/timeout 700))
                    (.click (single-node (xpath "//span[contains(text(), 'cancel')]")))
                    (>! ch :not-in-property))

                  (do (.click (single-node (xpath "//span[contains(text(), 'Submit request')]")))
                      (<! (async/timeout 1400))
                      ;; NOTE: may encounter
                      ;; 1. Duplicate request
                      ;; 2. Malform URL
                      ;; These show up as a modal dialog. Need to check for them
                      ;; Check for post submit modal dialog
                      (let [dup-req-node (single-node (xpath "//div[contains(text(), 'Duplicate request')]"))
                            malform-url-node (single-node (xpath "//div[contains(text(), 'Malformed URL')]"))
                            _ (<! (async/timeout 700))]
                        (cond (not (nil? dup-req-node)) (do
                                                          (.click (single-node (xpath "//span[contains(text(), 'Close')]")))
                                                          (>! ch :duplicate-request))
                              (not (nil? malform-url-node)) (do
                                                              (.click (single-node (xpath "//span[contains(text(), 'Close')]")))
                                                              (>! ch :malform-url))
                              :else (>! ch :success)
                              )
                        ))))


            ))

    ch))

; -- a message loop ---------------------------------------------------------------------------------------------------------
(defn process-message! [chan message]
  (let [{:keys [type] :as whole-msg} (common/unmarshall message)]
    (prn "CONTENT SCRIPT: process-message!: " whole-msg)
    (cond (= type :done-init-victims) (post-message! chan (common/marshall {:type :next-victim}))
          (= type :remove-url) (do (prn "handling :remove-url")
                                   (go
                                     (let [{:keys [victim removal-method url-type]} whole-msg
                                           request-status (<! (exec-new-removal-request victim
                                                                                        removal-method url-type))
                                           _ (<! (async/timeout 1200))]
                                       (prn "request-status: " request-status)
                                       (if (or (= :success request-status) (= :duplicate-request request-status))
                                         (post-message! chan (common/marshall {:type :success
                                                                               :url victim}))
                                         (post-message! chan (common/marshall {:type :skip-error
                                                                               :reason request-status
                                                                               :url victim
                                                                               })))
                                       )))
          (= type :done) (js/alert "DONE with bulk url removals!")
          )
    ))


; -- custom ui components  ------------------------------------------------------------------------------------------------



(defn ensure-english-setting []
  (let [url-parts (url (.. js/window -location -href))]
    (when-not (= "en" (get-in url-parts [:query "hl"]))
      (js/alert "Bulk URL Removal extension works properly only in English. Press OK to set the language to English.")
      (set! (.. js/window -location -href) (str (assoc-in url-parts [:query "hl"] "en")))
      )))



;; TODO: to be deprecated?
(defn setup-continue-ui [background-port]
  (let [continue-button-el (hipo/create [:div [:button {:type "button"
                                                        :on-click (fn []
                                                                    (post-message! background-port (common/marshall {:type :next-victim}))
                                                                    )}
                                               "Continue"
                                               ]])]
    (dommy/append! (sel1 :#create-removal_button) continue-button-el)
    ))





;; TODO: to be deprecated
(defn skip-has-already-been-removed-request
  "If the removal request has previously been made, update its status as removed"
  []
  ;; <span class="status-message-text>"
  ;; A removal request for this URL has already been made.
  ;; TODO: work in progress
  (go
    (if-let [r (when-let [el (sel1 "span.status-message-text")]
                 (when-let [[curr-removal-url _] (<! (current-removal-attempt))]
                   (when (= (-> el
                                dommy/text
                                clojure.string/trim) "A removal request for this URL has already been made.")
                     ;; NOTE: The removal timestamp is not accurate.
                     ;; It has been removed previously. Just need to update it so that it'll move along
                     (<! (update-storage curr-removal-url
                                         "status" "removed"
                                         "remove-ts" (tc/to-long (t/now))
                                         ))
                     )))]
      r
      "DO Nothing" ;; can't put nil on a channel
      )))



;; has to be used inside a go block
#_(defmacro with-wait [[action [_ [_ p] :as single-node-sexp] ]]
  `(let [path# ~p]
     (loop [n# (~'single-node (~'xpath path#))]
       (if (nil? n#)
         (do
           (<! (cljs.core.async/timeout 100))
           (recur (~'single-node (~'xpath path#))))
         (do
           (~'prn "n#: ")
           (.click n#))
         ))))


; -- main entry point -------------------------------------------------------------------------------------------------------

(defn init! []
  (let [_ (log "CONTENT SCRIPT: init")
        background-port (runtime/connect)]
    ;;;; new version
    (go
      (ensure-english-setting)
      (common/connect-to-background-page! background-port process-message!)
      )

    ;;;;; old version
    ;; Ask for the next victim if there's no failure.
    ;; If current-removal-attempt returns nil, that means
    ;; that there's no outstanding failure.
    #_(go
      (<! (async/timeout 1500)) ;; wait a bit for the ui to update
      (<! (skip-has-already-been-removed-request))
      (ensure-english-setting)
      (common/connect-to-background-page! background-port process-message!)

      (let [_ (prn "Inside go block.") ;;xxx
            curr-removal (<! (current-removal-attempt))
            _ (prn "curr-removal: " curr-removal) ;;xxx
            outstanding-failed-attempt? (->> curr-removal nil? not)]
        (if outstanding-failed-attempt?
          (setup-continue-ui background-port) ;; pause since we have an outstanding failure.
          (post-message! background-port (common/marshall {:type :next-victim}))
          )))
    ))
