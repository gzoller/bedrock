import http from 'k6/http';
import { check, sleep } from 'k6';

// Define test configuration
export let options = {
    stages: [
        { duration: '10s', target: 5000 },   // Start at 5000 RPS
        { duration: '30s', target: 10000 },  // Hold at 10000 RPS
        { duration: '10s', target: 0 },      // Ramp down
    ],
};

export default function () {
    let res = http.get('http://localhost:8000/say/hello'); // Adjust URL to match your app
    check(res, { "status was 200": (r) => r.status == 200 });
    sleep(0.5); // Simulate wait between requests
}
