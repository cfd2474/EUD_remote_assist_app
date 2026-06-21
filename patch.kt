    private fun startScreenShareService() {
        val intent = Intent(context, com.cfd2474.eudremoteassist.MainActivity::class.java).apply {
            action = com.cfd2474.eudremoteassist.MainActivity.ACTION_REQUEST_PROJECTION
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MainActivity for projection: ${e.message}")
        }
    }
