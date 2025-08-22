# Flask Template Directory Structure Fix Plan

## Problem Analysis

**Root Cause:** The AI agent created a Flask application with HTML templates in a `frontend/` directory instead of the Flask-conventional `templates/` directory. This causes `jinja2.exceptions.TemplateNotFound: index.html` errors because Flask's `render_template()` function looks for templates in a `templates/` directory by default.

**Error Details:**
- Flask app structure: `flappy_bird_flask/app.py`
- Template location: `flappy_bird_flask/frontend/index.html`
- Expected location: `flappy_bird_flask/templates/index.html`

## Immediate Fix Commands

### Step 1: Rename Directory Structure
```bash
# Navigate to the Flask project directory
cd flappy_bird_flask/

# Rename frontend directory to templates (Flask convention)
mv frontend templates

# Verify the structure
ls -la templates/
```

### Step 2: Verify Flask Configuration
```bash
# Check that app.py uses render_template correctly
grep -n "render_template" app.py

# Test the application
python3 app.py
```

### Step 3: Update Project Knowledge
```bash
# Refresh any project knowledge graphs or documentation
# Update internal file path references from frontend/ to templates/
```

## Prevention Strategy for Future Flask Projects

### 1. Mandatory Directory Structure Rules

For any Flask project, the agent MUST create:
- `templates/` directory for all HTML files
- `static/` directory for CSS, JavaScript, images
- `app.py` at the project root

### 2. Blueprint Template for Flask Projects

```json
{
  "framework": "Flask",
  "required_directories": [
    "templates",
    "static",
    "static/css",
    "static/js",
    "static/images"
  ],
  "main_file": "app.py",
  "template_engine": "Jinja2"
}
```

### 3. Validation Checklist

Before completing any Flask project:
- [ ] HTML files are in `templates/` directory
- [ ] Static assets are in `static/` directory
- [ ] `render_template()` calls match template file names
- [ ] Flask app can import and run without template errors

### 4. Agent Knowledge Update

The agent must learn:
1. **Framework conventions override logical naming**
2. **Flask requires `templates/` not `frontend/`**
3. **Always test template resolution before project completion**
4. **Include framework-specific directory structure in initial planning**

## Implementation Commands

```bash
# If the Flask project exists, execute these commands:

# 1. Fix directory structure
mv flappy_bird_flask/frontend flappy_bird_flask/templates

# 2. Verify fix
cd flappy_bird_flask && python3 -c "from flask import Flask, render_template; app = Flask(__name__); app.config['TESTING'] = True; print('Template resolution test passed')"

# 3. Test full application
python3 app.py
```

## Future Project Template

For all new Flask projects, use this structure:
```
project_name/
├── app.py
├── requirements.txt
├── templates/
│   ├── index.html
│   ├── base.html
│   └── ...
├── static/
│   ├── css/
│   ├── js/
│   └── images/
└── README.md
```

This ensures Flask framework compatibility from project inception.