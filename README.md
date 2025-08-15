# 🎹 Piano Tiles Game

A beautiful and modern web-based piano tiles game built with Python Flask and HTML5/CSS3/JavaScript.

## Features

- 🎵 **Piano Tiles Gameplay**: Tap falling tiles to play piano notes
- 📱 **Responsive Design**: Works on desktop and mobile devices
- 🎨 **Modern UI**: Beautiful gradient backgrounds and smooth animations
- 🔊 **Sound Effects**: Audio feedback when tapping tiles
- 📊 **Score Tracking**: Real-time score and lives display
- 🎮 **Touch Support**: Full touch support for mobile devices

## How to Play

1. **Objective**: Tap the tiles as they fall from the top of the screen
2. **Scoring**: Each successful tap earns 10 points
3. **Lives**: You start with 3 lives. Missing a tile costs 1 life
4. **Difficulty**: The game speeds up as you progress
5. **Game Over**: When you lose all lives, the game ends

## Installation

### Prerequisites

- Python 3.7 or higher
- pip (Python package installer)

### Setup

1. **Clone or download the project files**

2. **Install dependencies**:
   ```bash
   pip install -r requirements.txt
   ```

3. **Run the application**:
   ```bash
   python main.py
   ```

4. **Open your browser** and navigate to:
   ```
   http://localhost:5000
   ```

## Project Structure

```
piano-tiles-game/
├── main.py              # Flask application and game logic
├── requirements.txt     # Python dependencies
├── README.md           # This file
└── templates/
    └── index.html      # Game UI and frontend logic
```

## Game Controls

- **Mouse**: Click on tiles to tap them
- **Touch**: Tap tiles on mobile devices
- **Keyboard**: Not required (touch/mouse only)

## Technical Details

### Backend (Python/Flask)
- **Flask**: Web framework for the server
- **Game Logic**: Python class managing game state
- **API Endpoints**: RESTful API for game interactions

### Frontend (HTML/CSS/JavaScript)
- **HTML5**: Semantic markup structure
- **CSS3**: Modern styling with gradients and animations
- **JavaScript**: Game loop and user interaction handling
- **Web Audio API**: Sound effects for tile taps

## API Endpoints

- `GET /` - Main game page
- `GET /api/game-state` - Get current game state
- `POST /api/tap` - Handle tile tap (x, y coordinates)
- `POST /api/reset` - Reset game state
- `POST /api/update` - Update game state and generate new tiles

## Customization

### Changing Game Speed
Edit the `speed` variable in the `PianoTilesGame` class in `main.py`:
```python
self.speed = 2.0  # Initial speed in seconds
```

### Modifying Visual Style
Edit the CSS in `templates/index.html` to customize:
- Colors and gradients
- Tile appearance
- Animations
- Layout and sizing

### Adding Sound Effects
Modify the `playTapSound()` method in the JavaScript to add different audio effects.

## Troubleshooting

### Common Issues

1. **Port already in use**:
   - Change the port in `main.py`: `app.run(debug=True, host='0.0.0.0', port=5001)`

2. **Dependencies not found**:
   - Ensure you're using the correct Python environment
   - Run: `pip install -r requirements.txt`

3. **Game not responding**:
   - Check browser console for JavaScript errors
   - Ensure Flask server is running

### Browser Compatibility

- **Chrome/Edge**: Full support
- **Firefox**: Full support
- **Safari**: Full support
- **Mobile browsers**: Full touch support

## Development

### Running in Development Mode
The Flask app runs in debug mode by default, which provides:
- Auto-reload on code changes
- Detailed error messages
- Debug console

### Adding Features
1. **New Game Modes**: Extend the `PianoTilesGame` class
2. **Power-ups**: Add new tile types and effects
3. **Multiplayer**: Implement WebSocket support
4. **Leaderboards**: Add database integration

## License

This project is open source and available under the MIT License.

## Contributing

Feel free to submit issues, feature requests, or pull requests to improve the game!

---

**Enjoy playing Piano Tiles!** 🎹✨

