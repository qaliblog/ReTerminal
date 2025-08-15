from flask import Flask, render_template, jsonify, request
import random
import time

app = Flask(__name__)

# Game state
class PianoTilesGame:
    def __init__(self):
        self.score = 0
        self.lives = 3
        self.game_over = False
        self.tiles = []
        self.speed = 2.0  # seconds per tile
        self.last_tile_time = time.time()
        
    def generate_tile(self):
        """Generate a new tile at random position"""
        if time.time() - self.last_tile_time >= self.speed:
            tile = {
                'id': random.randint(1000, 9999),
                'x': random.randint(0, 3),  # 4 columns
                'y': 0,
                'active': True
            }
            self.tiles.append(tile)
            self.last_tile_time = time.time()
            # Increase speed gradually
            self.speed = max(0.5, self.speed - 0.01)
    
    def update_tiles(self):
        """Update tile positions and check for missed tiles"""
        for tile in self.tiles[:]:
            tile['y'] += 0.02  # Move tiles down
            if tile['y'] >= 1.0 and tile['active']:
                # Tile reached bottom without being tapped
                self.lives -= 1
                tile['active'] = False
                if self.lives <= 0:
                    self.game_over = True
        
        # Remove tiles that are off screen
        self.tiles = [tile for tile in self.tiles if tile['y'] < 1.2]
    
    def tap_tile(self, x, y):
        """Handle tile tap"""
        if self.game_over:
            return False
            
        # Find the closest active tile to the tap position
        closest_tile = None
        min_distance = float('inf')
        
        for tile in self.tiles:
            if tile['active']:
                # Check if tap is within tile bounds
                tile_x = tile['x'] * 0.25  # Convert to 0-1 range
                tile_y = tile['y']
                
                if (abs(x - tile_x) < 0.125 and  # Within tile width
                    abs(y - tile_y) < 0.1):      # Within tile height
                    distance = abs(y - tile_y)
                    if distance < min_distance:
                        min_distance = distance
                        closest_tile = tile
        
        if closest_tile:
            closest_tile['active'] = False
            self.score += 10
            return True
        
        return False
    
    def reset(self):
        """Reset game state"""
        self.score = 0
        self.lives = 3
        self.game_over = False
        self.tiles = []
        self.speed = 2.0
        self.last_tile_time = time.time()

# Global game instance
game = PianoTilesGame()

@app.route('/')
def index():
    return render_template('index.html')

@app.route('/api/game-state')
def get_game_state():
    return jsonify({
        'score': game.score,
        'lives': game.lives,
        'game_over': game.game_over,
        'tiles': game.tiles
    })

@app.route('/api/tap', methods=['POST'])
def tap():
    data = request.get_json()
    x = data.get('x', 0)
    y = data.get('y', 0)
    
    success = game.tap_tile(x, y)
    return jsonify({'success': success})

@app.route('/api/reset', methods=['POST'])
def reset_game():
    game.reset()
    return jsonify({'success': True})

@app.route('/api/update', methods=['POST'])
def update_game():
    game.generate_tile()
    game.update_tiles()
    return jsonify({
        'score': game.score,
        'lives': game.lives,
        'game_over': game.game_over,
        'tiles': game.tiles
    })

if __name__ == '__main__':
    app.run(debug=True, host='0.0.0.0', port=5000)