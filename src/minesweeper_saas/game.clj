(ns minesweeper-saas.game
  (:require [clojure.set]))

(def blank-tile "" #{"hidden" 0})

(defn increment-number [current]
  (let [result (+ current 1)]
    (if (and (>= result 0) (< result 8))
      result
      "<error>")))

(defn tile-number [tile]
  (cond (contains? tile 0) 0
        (contains? tile 1) 1
        (contains? tile 2) 2
        (contains? tile 3) 3
        (contains? tile 4) 4
        (contains? tile 5) 5
        (contains? tile 6) 6
        (contains? tile 7) 7
        (contains? tile 8) 8
        :else "<error>"))

(defn add-tag [tile tag]
  (conj (set tile) tag))

(defn remove-tag
  ([tile tag]
   (disj (set tile) tag))
  ([tile tag1 tag2]
   (disj (disj (set tile) tag1) tag2)))

(defn random-indexes
  "Generate some random numbers, non-repeating, in a range from 0 to n"
  [how-many n]
  (take how-many (shuffle (range 0 n))))

(defn place-mines
  "Place mines randomly around a tile vector"
  [tiles mine-count]
  (loop [tiles   tiles
         indexes (random-indexes mine-count (count tiles))
         index   (first indexes)]
    (if index
      (recur (update tiles index #(conj % "mine"))
             (rest indexes)
             (first indexes))
      tiles)))

(defn index->xy [index width]
  (let [x (mod index width)
        y (/ (- index x) width)]
    {:x x, :y y}))

(defn xy->index [x y width]
  (+ (* y width) x))

(defn relative-index
  "Returns the index relative to the given index, offset by dx and dy,
  or :out-of-bounds if the offset not within the range."
  [index width height dx dy]
  (let [{:keys [x y]} (index->xy index width)
        dest-x (+ x dx)
        dest-y (+ y dy)
        dest-index (xy->index dest-x dest-y width)]
    (if (and (>= dest-x 0) (< dest-x width) (>= dest-y 0) (< dest-y height))
      dest-index
      :out-of-bounds)))

(defn update-relative-tile
  "Applies f to the tile relative to the given position
  or nil if out of bounds"
  [tiles index width height dx dy f]
  (let [dest-index (relative-index index width height dx dy)]
    (if (= dest-index :out-of-bounds)
      tiles
      (update tiles dest-index f))))

(defn increment-tile
  [tile]
  (let [current-number (tile-number tile)
        new-number (increment-number current-number)]
    (-> tile
        (remove-tag current-number)
        (add-tag new-number))))

(defn increment-surrounding
  "Takes an index and increments its surrounding 8 tiles'
  counts, if they are not themselves mines."
  [tiles index width height]
  (-> tiles
      (update-relative-tile index width height -1 -1 increment-tile)   ;; up left
      (update-relative-tile index width height  0 -1 increment-tile)   ;; up
      (update-relative-tile index width height  1 -1 increment-tile)   ;; up right
      (update-relative-tile index width height -1  0 increment-tile)   ;; left
      (update-relative-tile index width height  1  0 increment-tile)   ;; right
      (update-relative-tile index width height -1  1 increment-tile)   ;; down left
      (update-relative-tile index width height  0  1 increment-tile)   ;; down
      (update-relative-tile index width height  1  1 increment-tile))) ;; down right

(defn assign-numbers
  "Increment the tiles that are surrounding miles"
  [tiles width height]
  (let [tile-count (count tiles)]
    (loop [tiles tiles
           index 0]
      (if (>= index tile-count)
        tiles
        (let [tile (set (get tiles index))]
          (if (contains? tile "mine")
            (recur (increment-surrounding tiles index width height)
                   (inc index))
            (recur tiles
                   (inc index))))))))

(defn generate-tiles
  "Create a starting minefield for the given parameters"
  [mine-count width height]
  (let [blank-tiles (vec (repeat (* width height) blank-tile))]
    (-> blank-tiles
        (place-mines mine-count)
        (assign-numbers width height))))

(defn reset
  "Create a starting minefield with predefined parameters"
  []
  (let [mine-count 10
        height     8
        width      10]
    {:tiles  (generate-tiles mine-count width height)
     :height height
     :width  width}))

(defn reveal-mines
  "Remove any hidden tags and flags from mines"
  [tiles]
  (loop [tiles tiles
         index 0]
    (let [tile (get tiles index)]
      (if tile
        (if (contains? (set tile) "mine")
          (recur (-> tiles
                     (update index #(remove-tag % "hidden" "flag")))
                 (inc index))
          (recur tiles
                 (inc index)))
        tiles))))

(defn game-over [state]
  (-> state
      (assoc :game-over true)
      (assoc :tiles (reveal-mines (:tiles state)))))

(defn determine-neighbor-indexes
  [index open closed tiles width height]
  (let [up-left    (relative-index index width height -1 -1)
        up         (relative-index index width height  0 -1)
        up-right   (relative-index index width height  1 -1)
        left       (relative-index index width height -1  0)
        right      (relative-index index width height  1  0)
        down-left  (relative-index index width height -1  1)
        down       (relative-index index width height  0  1)
        down-right (relative-index index width height  1  1)]
    (-> (set [up-left up up-right left right down-left down down-right])
        (clojure.set/difference closed #{:out-of-bounds})
        (clojure.set/union open))))

(defn clear-fill [start-index state]
  (loop [state  state
         open   #{start-index}
         closed #{}]
    (if (empty? open)
      state
      (let [index (first open)]
        (if (contains? closed index)
          (recur state (rest open) closed)
          (do
            (let [tile (set ((:tiles state) index))]
              (if (contains? tile 0)
                (recur (update-in state
                                  [:tiles index]
                                  #(remove-tag % "hidden" "flag"))
                       (determine-neighbor-indexes index
                                                   open
                                                   closed
                                                   (:tiles state)
                                                   (:width state)
                                                   (:height state))
                       (conj closed index))
                (recur (update-in state
                                  [:tiles index]
                                  #(remove-tag % "hidden" "flag"))
                       (rest open)
                       (conj closed index))))))))))

(defn check-win
  "If the only hidden tiles left are also mines, the player has won."
  [{:keys [tiles] :as state}]
  (if (not-any? (fn [tile-vector] (let [tile (set tile-vector)]
                                    (and (contains? tile "hidden")
                                         (not (contains? tile "mine")))))
                tiles)
    (-> state
        game-over
        (assoc :win true))
    state))

(defn clear
  "Attempt to either clear tile successfully, or hit a mine.
  Tile must be a hidden tile"
  [index state]
  (let [new-state
        (let [path-to-tile [:tiles index]
              tile (set (get-in state path-to-tile))]
          (cond
            (contains? tile "mine") (game-over state)
            (contains? tile "hidden") (clear-fill index state)
            :else state))]
    (if (:game-over new-state)
      new-state
      (check-win new-state))))

(defn flag
  "Toggle the flag of a tile. Tile must be a hidden tile."
  [index state]
  (let [path-to-tile [:tiles index]
        tile (set (get-in state path-to-tile))]
    (cond (contains? tile "flag") (update-in state
                                             path-to-tile
                                             #(remove-tag % "flag"))
          (contains? tile "hidden") (update-in state
                                               path-to-tile
                                               #(add-tag % "flag"))
          :else state)))

(defn apply-pick
  "Determine and carry out the pick of a tile"
  [type index state]
  (case type
    :clear (clear index state)
    :flag (flag index state)
    state))

(defn pick [{:keys [pick-grid-index] :as prev-state}
            type]
  (if (true? (:game-over prev-state))
    prev-state
    (apply-pick type
                pick-grid-index
                (dissoc prev-state :pick-grid-index))))
