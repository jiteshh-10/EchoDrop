package com.dev.echodrop.screens;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.dev.echodrop.R;
import com.dev.echodrop.databinding.ScreenCreateChatBinding;
import com.dev.echodrop.db.ChatEntity;
import com.dev.echodrop.viewmodels.ChatViewModel;
import com.google.android.material.snackbar.Snackbar;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.EnumMap;
import java.util.Map;

/**
 * Screen for creating a new private chat.
 *
 * <p>Generates a unique 8-character code, displays it in XXXX-XXXX format,
 * and allows copying, QR generation, and code regeneration before
 * creating the chat.</p>
 */
public class CreateChatFragment extends Fragment {

    private ScreenCreateChatBinding binding;
    private ChatViewModel viewModel;
    private String currentCode;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = ScreenCreateChatBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel = new ViewModelProvider(requireActivity()).get(ChatViewModel.class);

        generateNewCode();
        setupListeners();
    }

    // ──────────────────── Setup ────────────────────

    private void setupListeners() {
        binding.btnBack.setOnClickListener(v -> navigateBack());
        binding.btnCopy.setOnClickListener(v -> copyCode());
        binding.btnQr.setOnClickListener(v -> toggleQr());
        binding.btnNewCode.setOnClickListener(v -> generateNewCode());
        binding.btnCreate.setOnClickListener(v -> createChat());
    }

    private void generateNewCode() {
        currentCode = ChatEntity.generateCode();
        binding.codeDisplay.setText(ChatEntity.formatCode(currentCode));
        // Hide QR if visible — new code invalidates old QR
        binding.qrContainer.setVisibility(View.GONE);
    }

    // ──────────────────── Actions ────────────────────

    private void copyCode() {
        final ClipboardManager clipboard = (ClipboardManager)
                requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        final String formatted = ChatEntity.formatCode(currentCode);
        clipboard.setPrimaryClip(ClipData.newPlainText("EchoDrop Room Code", formatted));
        Snackbar.make(binding.getRoot(), R.string.chat_code_copied, Snackbar.LENGTH_SHORT).show();
    }

    private void toggleQr() {
        if (binding.qrContainer.getVisibility() == View.VISIBLE) {
            binding.qrContainer.setVisibility(View.GONE);
        } else {
            final Bitmap qr = generateQrBitmap(ChatEntity.formatCode(currentCode), 512);
            if (qr != null) {
                binding.qrImage.setImageBitmap(qr);
                binding.qrContainer.setVisibility(View.VISIBLE);
            }
        }
    }

    private void createChat() {
        final String name = binding.inputName.getText() != null
                ? binding.inputName.getText().toString().trim()
                : null;
        final String chatName = (name != null && !name.isEmpty()) ? name : null;

        viewModel.createChat(currentCode, chatName);
        Snackbar.make(binding.getRoot(), R.string.chat_created, Snackbar.LENGTH_SHORT).show();

        // Navigate back to chat list
        navigateBack();
    }

    // ──────────────────── QR generation ────────────────────

    /**
     * Generates a QR code bitmap from a string using ZXing core.
     *
     * @param content the text to encode.
     * @param size    width/height in pixels.
     * @return Bitmap or null on failure.
     */
    @Nullable
    static Bitmap generateQrBitmap(@NonNull String content, int size) {
        try {
            final Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);

            final QRCodeWriter writer = new QRCodeWriter();
            final BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints);

            final Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bitmap;
        } catch (WriterException e) {
            return null;
        }
    }

    // ──────────────────── Navigation ────────────────────

    private void navigateBack() {
        if (getParentFragmentManager().getBackStackEntryCount() > 0) {
            getParentFragmentManager().popBackStack();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
