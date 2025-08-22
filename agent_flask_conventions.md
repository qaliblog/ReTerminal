# Agent Upgrade: Flask Framework Conventions

## Critical Update Required

This document outlines mandatory updates to the AI agent's Flask project generation logic to prevent template directory structure errors.

## Problem Identified

The agent incorrectly created Flask applications with templates in `frontend/` directories instead of Flask's required `templates/` directory, causing `jinja2.exceptions.TemplateNotFound` errors.

## Mandatory Agent Behavior Changes

### 1. Flask Project Initialization Rules

When generating ANY Flask project, the agent MUST:

```python
# Required directory structure for Flask projects
FLASK_REQUIRED_DIRS = [
    "templates",      # For all HTML/Jinja2 templates
    "static",         # For CSS, JS, images
    "static/css",     # Stylesheets
    "static/js",      # JavaScript files
    "static/images"   # Image assets
]
```

### 2. Template Location Enforcement

- **NEVER** create `frontend/` directories for Flask projects
- **ALWAYS** use `templates/` for HTML files
- **ALWAYS** use `static/` for assets

### 3. Blueprint Logic Update

The agent's project planning must include:

```json
{
  "task_type": "create_flask_project",
  "framework_requirements": {
    "name": "Flask",
    "template_engine": "Jinja2",
    "required_structure": {
      "templates": "HTML templates directory (Flask convention)",
      "static": "Static assets directory (CSS, JS, images)",
      "app.py": "Main Flask application file"
    }
  },
  "validation_steps": [
    "verify_templates_directory_exists",
    "verify_static_directory_exists", 
    "test_template_resolution"
  ]
}
```

### 4. Pre-completion Validation

Before marking any Flask project complete, the agent MUST:

1. Verify `templates/` directory exists
2. Verify all HTML files are in `templates/`
3. Test that `render_template()` calls resolve correctly
4. Ensure no HTML files remain in incorrectly named directories

## Implementation Priority

**CRITICAL PRIORITY**: This is a framework-breaking error that renders Flask applications non-functional. This update must be implemented immediately.

## Framework Convention Rules

### General Principle
**Framework conventions ALWAYS override logical naming preferences**

### Specific Rules by Framework

#### Flask
- Templates: `templates/` (not `frontend/`, `views/`, or `pages/`)
- Static files: `static/` (not `assets/`, `public/`, or `resources/`)
- Main app: `app.py` or `main.py`

#### Django  
- Templates: `templates/` or app-specific `app/templates/app/`
- Static files: `static/` or app-specific `app/static/app/`
- Settings: `settings.py`

#### FastAPI
- Templates: `templates/` (when using Jinja2)
- Static files: `static/`
- Main app: `main.py`

## Testing Requirements

For each Flask project, the agent must execute:

```bash
# Template resolution test
python3 -c "
from flask import Flask, render_template
app = Flask(__name__)
try:
    with app.app_context():
        # Test template exists and can be found
        app.jinja_env.get_template('index.html')
    print('✓ Template resolution successful')
except Exception as e:
    print(f'✗ Template resolution failed: {e}')
    exit(1)
"
```

## Agent Knowledge Base Update

Add to agent's core knowledge:

1. **Flask projects require `templates/` directory for HTML files**
2. **Never use `frontend/` for Flask template storage**
3. **Framework conventions are non-negotiable requirements**
4. **Always validate framework-specific structure before project completion**

This prevents the critical `jinja2.exceptions.TemplateNotFound` error and ensures Flask applications work immediately upon generation.