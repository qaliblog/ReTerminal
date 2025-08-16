# Flask Calculator

A modern, responsive web calculator built with Flask, featuring a beautiful UI and full mathematical functionality.

## Features

- 🧮 Full mathematical operations (+, -, ×, ÷)
- 🎨 Modern, responsive design
- ⌨️ Keyboard support
- 📱 Mobile-friendly interface
- 🔒 Safe expression evaluation
- ⚡ Real-time calculations

## Installation

1. **Clone or download the project files**

2. **Install Python dependencies:**
   ```bash
   pip install -r requirements.txt
   ```

3. **Run the application:**
   ```bash
   python app.py
   ```

4. **Open your browser and navigate to:**
   ```
   http://localhost:5000
   ```

## Usage

### Mouse/Touch
- Click the calculator buttons to input numbers and operations
- Press `=` to calculate the result
- Press `C` to clear the display

### Keyboard
- Use number keys (0-9) and operators (+, -, *, /)
- Press `Enter` or `=` to calculate
- Press `Escape` or `C` to clear
- Use `Backspace` to delete last character
- Use parentheses `(` and `)` for complex expressions

## Supported Operations

- **Basic arithmetic:** Addition (+), Subtraction (-), Multiplication (×), Division (÷)
- **Parentheses:** Complex expressions with proper order of operations
- **Decimal numbers:** Support for decimal point operations
- **Error handling:** Division by zero, invalid expressions, and network errors

## Project Structure

```
flask_calculator/
├── app.py                 # Main Flask application
├── requirements.txt       # Python dependencies
├── README.md             # This file
└── app/
    ├── templates/
    │   └── calculator.html  # Calculator HTML template
    └── static/
        ├── style.css        # Calculator styling
        └── script.js        # Frontend JavaScript
```

## Security Features

- Input validation to prevent code injection
- Safe expression evaluation
- Division by zero protection
- Character filtering for mathematical expressions only

## Browser Compatibility

- Chrome (recommended)
- Firefox
- Safari
- Edge
- Mobile browsers

## Development

To run in development mode with auto-reload:
```bash
python app.py
```

The application will be available at `http://localhost:5000` with debug mode enabled.

## License

This project is open source and available under the MIT License.
