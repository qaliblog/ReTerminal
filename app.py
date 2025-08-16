from flask import Flask, render_template, request, jsonify
import re

app = Flask(__name__)

def evaluate_expression(expression):
    """Safely evaluate mathematical expressions"""
    try:
        # Remove any whitespace
        expression = expression.replace(' ', '')
        
        # Only allow digits, operators, and parentheses
        if not re.match(r'^[\d\+\-\*\/\(\)\.]+$', expression):
            return "Error: Invalid characters"
        
        # Check for division by zero
        if '/0' in expression and not re.search(r'/0[1-9]', expression):
            return "Error: Division by zero"
        
        # Evaluate the expression
        result = eval(expression)
        
        # Check if result is finite
        if not (isinstance(result, (int, float)) and result == result):
            return "Error: Invalid calculation"
        
        return str(result)
    except Exception as e:
        return "Error: Invalid expression"

@app.route('/')
def index():
    return render_template('calculator.html')

@app.route('/calculate', methods=['POST'])
def calculate():
    data = request.get_json()
    expression = data.get('expression', '')
    
    if not expression:
        return jsonify({'result': '0'})
    
    result = evaluate_expression(expression)
    return jsonify({'result': result})

if __name__ == '__main__':
    app.run(debug=True, host='0.0.0.0', port=5000)