const { ProxyServerSystem } = require('./server');

let proxyServer = null;

/**
 * Restart the proxy server.
 */
async function restartProxy() {
    console.log('AIStudioBuildProxy: Restarting proxy server in 5 seconds...');

    if (proxyServer) {
        try {
            await proxyServer.stop();
        } catch (err) {
            console.error('AIStudioBuildProxy: Error stopping server during restart:', err.message);
        }
    }

    setTimeout(async () => {
        console.log('AIStudioBuildProxy: Attempting to restart...');
        proxyServer = new ProxyServerSystem();

        proxyServer.on('error', (err) => {
            console.error('AIStudioBuildProxy: Proxy server error:', err.message);
            restartProxy();
        });

        try {
            await proxyServer.start();
            console.log('AIStudioBuildProxy: Proxy server restarted successfully');
        } catch (error) {
            console.error('AIStudioBuildProxy: Failed to restart proxy server', error);
            restartProxy();
        }
    }, 5000);
}

/**
 * Initialize plugin.
 * @param {import('express').Router} router Express router
 * @returns {Promise<any>} Promise that resolves when plugin is initialized
 */
async function init(router) {
    console.log('AIStudioBuildProxy plugin initializing...');

    // Initialize and start the proxy server
    proxyServer = new ProxyServerSystem();

    // Handle server errors to prevent crashes and auto-restart
    proxyServer.on('error', (err) => {
        console.error('AIStudioBuildProxy: Proxy server error:', err.message);
        restartProxy();
    });

    try {
        await proxyServer.start();
        console.log('AIStudioBuildProxy: Proxy server started successfully');
    } catch (error) {
        console.error('AIStudioBuildProxy: Failed to start proxy server', error);
        // Retry start after a delay
        setTimeout(restartProxy, 5000);
    }

    // Example route: /api/plugins/ai-studio-build-proxy/test
    router.get('/test', (req, res) => {
        res.send('Hello from AIStudioBuildProxy! Proxy server should be running on ports 8889/9998.');
    });

    return Promise.resolve();
}

/**
 * Clean up plugin resources on server shutdown.
 * @returns {Promise<void>}
 */
async function exit() {
    console.log('AIStudioBuildProxy plugin unloading...');

    if (proxyServer) {
        await proxyServer.stop();
        proxyServer = null;
    }

    return Promise.resolve();
}

module.exports = {
    init,
    exit,
    info: {
        id: 'ai-studio-build-proxy',
        name: 'AI Studio Build Proxy',
        description: 'A proxy plugin for AI Studio builds',
    },
};
