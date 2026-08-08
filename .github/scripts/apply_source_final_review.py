from pathlib import Path
import argparse

parser = argparse.ArgumentParser()
parser.add_argument("mode", choices=["bypass", "wording"])
args = parser.parse_args()

MOBILE = Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerMobileFragment.kt")
TV = Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerTvFragment.kt")
STRINGS = Path("app/src/main/res/values/strings.xml")


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected exactly one match in {path} but found {count}: {old[:80]!r}")
    path.write_text(text.replace(old, new, 1))


if args.mode == "bypass":
    replace_once(
        MOBILE,
        '''            if (result.resultCode != android.app.Activity.RESULT_OK || cookies.isNullOrBlank()) {
                waitingForBypass = false
                return@registerForActivityResult
            }

            val bypassUrl = servers.firstOrNull { isSerienStreamBypassUrl(it.id) }?.id
            if (bypassUrl.isNullOrBlank()) {
                waitingForBypass = false
                return@registerForActivityResult
            }

            applyBypassCookies(bypassUrl, cookies)
''',
        '''            val bypassServer = servers.firstOrNull { isSerienStreamBypassUrl(it.id) }
            if (result.resultCode != android.app.Activity.RESULT_OK || cookies.isNullOrBlank()) {
                if (bypassServer != null) {
                    recoverFromBypassFailure(bypassServer)
                } else {
                    showPlaybackUnavailable(messageRes = R.string.player_sources_load_failed_message)
                }
                return@registerForActivityResult
            }

            val bypassUrl = bypassServer?.id
            if (bypassUrl.isNullOrBlank()) {
                showPlaybackUnavailable(messageRes = R.string.player_sources_load_failed_message)
                return@registerForActivityResult
            }

            applyBypassCookies(bypassUrl, cookies)
''',
    )

    replace_once(
        MOBILE,
        '''                            if (bypassUrl.isNullOrBlank()) {
                                waitingForBypass = false
                                Toast.makeText(requireContext(), "Unable to open s.to bypass page.", Toast.LENGTH_SHORT).show()
                                return@collect
                            }
''',
        '''                            if (bypassUrl.isNullOrBlank()) {
                                Log.e("PlayerMobileFragment", "Unable to prepare SerienStream bypass URL")
                                recoverFromBypassFailure(sToServer)
                                return@collect
                            }
''',
    )

    mobile_status = '''    private fun showSourceStatus(message: String) {
        sourceStatusToast?.cancel()
        sourceStatusToast = Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).also {
            it.show()
        }
    }
'''
    mobile_helper = mobile_status + '''
    private fun recoverFromBypassFailure(server: Video.Server) {
        waitingForBypass = false
        servers.filter { isSerienStreamBypassUrl(it.id) }.forEach(failedServers::add)

        val nextServer = nextUnfailedServerAfter(server)
        if (nextServer != null) {
            Log.w(
                "PlayerMobileFragment",
                "SerienStream bypass unavailable, trying next server: ${nextServer.name}",
            )
            showSourceStatus(
                getString(
                    R.string.player_source_trying_next,
                    server.name,
                    nextServer.name,
                )
            )
            viewModel.selectVideo(nextServer)
        } else {
            showPlaybackUnavailable(messageRes = R.string.player_sources_load_failed_message)
        }
    }
'''
    replace_once(MOBILE, mobile_status, mobile_helper)

    replace_once(
        TV,
        '''                            if (bypassUrl.isNullOrBlank()) {
                                waitingForBypass = false
                                Toast.makeText(
                                    requireContext(),
                                    "Unable to prepare TV bypass page.",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@collect
                            }
''',
        '''                            if (bypassUrl.isNullOrBlank()) {
                                Log.e("PlayerTvFragment", "Unable to prepare SerienStream bypass URL")
                                recoverFromBypassFailure(sToServer)
                                return@collect
                            }
''',
    )

    replace_once(
        TV,
        '''                            if (actualPort == -1) {
                                clearBypassSession()
                                Toast.makeText(
                                    requireContext(),
                                    "Unable to start TV bypass. Please try again.",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@collect
                            }
''',
        '''                            if (actualPort == -1) {
                                Log.e("PlayerTvFragment", "Unable to start SerienStream bypass server")
                                recoverFromBypassFailure(sToServer)
                                return@collect
                            }
''',
    )

    replace_once(
        TV,
        '''                            val wsUrl = BypassWebSocketEndpointHelper.getAdvertisedWsUrl(actualPort)
                                ?: return@collect
''',
        '''                            val wsUrl = BypassWebSocketEndpointHelper.getAdvertisedWsUrl(actualPort)
                            if (wsUrl.isNullOrBlank()) {
                                Log.e("PlayerTvFragment", "Unable to advertise SerienStream bypass endpoint")
                                recoverFromBypassFailure(sToServer)
                                return@collect
                            }
''',
    )

    replace_once(
        TV,
        '''                            requireActivity().runOnUiThread {
                                showQrDialog(qrContent)
                                Log.d("Bypass", "Advertised WS URL: $wsUrl")
                            }
''',
        '''                            requireActivity().runOnUiThread {
                                if (showQrDialog(qrContent, sToServer)) {
                                    Log.d("Bypass", "Advertised WS URL: $wsUrl")
                                } else {
                                    Log.e("PlayerTvFragment", "Unable to generate SerienStream bypass QR code")
                                    recoverFromBypassFailure(sToServer)
                                }
                            }
''',
    )

    tv_status = '''    private fun showSourceStatus(message: String) {
        sourceStatusToast?.cancel()
        sourceStatusToast = Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).also {
            it.show()
        }
    }
'''
    tv_helper = tv_status + '''
    private fun recoverFromBypassFailure(server: Video.Server) {
        clearBypassSession(dismissDialog = true)
        servers.filter { isSerienStreamBypassUrl(it.id) }.forEach(failedServers::add)

        val nextServer = nextUnfailedServerAfter(server)
        if (nextServer != null) {
            Log.w(
                "PlayerTvFragment",
                "SerienStream bypass unavailable, trying next server: ${nextServer.name}",
            )
            showSourceStatus(
                getString(
                    R.string.player_source_trying_next,
                    server.name,
                    nextServer.name,
                )
            )
            viewModel.selectVideo(nextServer)
        } else {
            showPlaybackUnavailable(messageRes = R.string.player_sources_load_failed_message)
        }
    }
'''
    replace_once(TV, tv_status, tv_helper)

    replace_once(
        TV,
        '    private fun showQrDialog(content: String) {\n',
        '    private fun showQrDialog(content: String, bypassServer: Video.Server): Boolean {\n',
    )
    replace_once(
        TV,
        '        val bitmap = QrUtils.generate(content, qrSize) ?: return\n',
        '        val bitmap = QrUtils.generate(content, qrSize) ?: return false\n',
    )
    replace_once(
        TV,
        '''            .setOnCancelListener {
                Log.d("Bypass", "QR dialog cancelled")
                clearBypassSession(dismissDialog = false)
            }
''',
        '''            .setOnCancelListener {
                Log.d("Bypass", "QR dialog cancelled")
                recoverFromBypassFailure(bypassServer)
            }
''',
    )
    replace_once(
        TV,
        '''        qrDialog?.show()
        qrDialog?.window?.setLayout(dialogWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun isSerienStreamBypassUrl(url: String): Boolean {
''',
        '''        qrDialog?.show()
        qrDialog?.window?.setLayout(dialogWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
        return true
    }

    private fun isSerienStreamBypassUrl(url: String): Boolean {
''',
    )

elif args.mode == "wording":
    replace_once(
        STRINGS,
        '<string name="player_no_sources_message">No playback sources are available for this title.</string>',
        '<string name="player_no_sources_message">No playback sources are currently available for this title.</string>',
    )
