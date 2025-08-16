let currentExpression = '0';
let lastResult = '0';

function updateDisplay() {
    document.getElementById('expression').textContent = currentExpression;
    document.getElementById('result').textContent = lastResult;
}

function appendToExpression(value) {
    if (currentExpression === '0' && value !== '.') {
        currentExpression = value;
    } else {
        currentExpression += value;
    }
    updateDisplay();
}

function clearDisplay() {
    currentExpression = '0';
    lastResult = '0';
    updateDisplay();
}

function calculate() {
    if (currentExpression === '0') {
        return;
    }
    
    fetch('/calculate', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify({
            expression: currentExpression
        })
    })
    .then(response => response.json())
    .then(data => {
        lastResult = data.result;
        
        if (lastResult.startsWith('Error:')) {
            document.getElementById('result').classList.add('error');
            setTimeout(() => {
                document.getElementById('result').classList.remove('error');
            }, 500);
        } else {
            currentExpression = lastResult;
        }
        
        updateDisplay();
    })
    .catch(error => {
        console.error('Error:', error);
        lastResult = 'Error: Network issue';
        updateDisplay();
    });
}

// Keyboard support
document.addEventListener('keydown', function(event) {
    const key = event.key;
    
    if (key >= '0' && key <= '9' || key === '.') {
        appendToExpression(key);
    } else if (key === '+' || key === '-' || key === '*' || key === '/') {
        appendToExpression(key);
    } else if (key === '(' || key === ')') {
        appendToExpression(key);
    } else if (key === 'Enter' || key === '=') {
        calculate();
    } else if (key === 'Escape' || key === 'c' || key === 'C') {
        clearDisplay();
    } else if (key === 'Backspace') {
        if (currentExpression.length > 1) {
            currentExpression = currentExpression.slice(0, -1);
        } else {
            currentExpression = '0';
        }
        updateDisplay();
    }
});

// Initialize display
updateDisplay();