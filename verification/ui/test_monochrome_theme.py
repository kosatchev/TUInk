import unittest

from monochrome_theme import (assert_monochrome_output, chromatic_sgr, default_bold_sgr,
                         gray_code_bold_sgr)


class MonochromeSgrAnalysisTest(unittest.TestCase):
    def test_old_default_foreground_bold_sequence_is_rejected_without_chromatic_colors(self):
        data = b'\x1b[39;49;1mheading'
        self.assertEqual([], chromatic_sgr(data))
        self.assertEqual(['39;49;1'], default_bold_sgr(data))
        with self.assertRaisesRegex(AssertionError, 'DEFAULT foreground with bold'):
            assert_monochrome_output(data, 'old Monochrome')

    def test_foreground_is_checked_after_the_complete_command(self):
        for command in (b'1;38;5;253;48;5;236', b'39;49;1;38;5;253;48;5;236'):
            with self.subTest(command=command):
                data = b'\x1b[' + command + b'mkeyword'
                self.assertEqual([], default_bold_sgr(data))
                self.assertTrue(gray_code_bold_sgr(data))
                assert_monochrome_output(data, 'complete command')

    def test_gray_bold_code_is_recognized_for_indexed_rgb_colon_and_ansi16(self):
        for command in (b'38;5;253;48;5;236;1', b'38;2;218;218;218;48;2;48;48;48;1',
                        b'38:2::218:218:218;48:2:0:48:48:48;1', b'97;40;1'):
            with self.subTest(command=command):
                data = b'\x1b[' + command + b'mkeyword'
                self.assertEqual([], chromatic_sgr(data))
                self.assertEqual([], default_bold_sgr(data))
                self.assertEqual([command.decode()], gray_code_bold_sgr(data))
                assert_monochrome_output(data, 'neutral palette')

    def test_inherited_foreground_and_background_apply_to_later_bold(self):
        data = b'\x1b[38;5;253;48;5;236mcode\x1b[1mkeyword\x1b[49mplain'
        self.assertEqual([], default_bold_sgr(data))
        self.assertEqual(['1'], gray_code_bold_sgr(data))
        self.assertEqual(['39'], default_bold_sgr(data + b'\x1b[39mdefault'))

    def test_reset_empty_reset_and_normal_intensity_update_the_state(self):
        self.assertEqual(['1'], default_bold_sgr(b'\x1b[37;1mwhite\x1b[0mreset\x1b[1mbold'))
        self.assertEqual(['1'], default_bold_sgr(b'\x1b[37;1mwhite\x1b[mreset\x1b[1mbold'))
        self.assertEqual([], default_bold_sgr(b'\x1b[37;1mwhite\x1b[22;39mnormal'))
        self.assertEqual([], default_bold_sgr(b'\x1b[39;49;1;22mnormal'))
        self.assertEqual(['1;37;;1'], default_bold_sgr(b'\x1b[1;37;;1mbold'))

    def test_extended_color_components_are_not_interpreted_as_sgr_attributes(self):
        self.assertEqual(['39'], default_bold_sgr(b'\x1b[1;38;2;0;0;0mblack\x1b[39mdefault'))
        self.assertEqual([], default_bold_sgr(b'\x1b[38;2;1;1;1mgray\x1b[39mdefault'))
        self.assertEqual(['39'], default_bold_sgr(b'\x1b[1;38;5;0mblack\x1b[39mdefault'))

    def test_basic_and_extended_chromatic_channels_are_still_reported(self):
        for command in (b'34', b'44', b'94', b'104', b'38;5;21', b'48;2;48;49;48',
                        b'58:2::218:217:218'):
            with self.subTest(command=command):
                data = b'\x1b[' + command + b'mtext'
                self.assertEqual([command.decode()], chromatic_sgr(data))
                with self.assertRaisesRegex(AssertionError, 'chromatic SGR'):
                    assert_monochrome_output(data, 'chromatic palette')

    def test_all_indexed_grayscale_representations_are_neutral(self):
        for index in (0, 7, 8, 15, 16, 59, 102, 145, 188, 231, *range(232, 256)):
            with self.subTest(index=index):
                data = f'\x1b[38;5;{index};48;5;{index}m'.encode()
                self.assertEqual([], chromatic_sgr(data))

    def test_explicit_gray_bold_code_is_required_even_when_output_is_neutral(self):
        for data in (b'\x1b[38;5;250mplain', b'\x1b[38;5;253;1mheading',
                     b'\x1b[38;5;253;48;5;236mnonbold code'):
            with self.subTest(data=data):
                with self.assertRaisesRegex(AssertionError, 'explicit gray bold keyword'):
                    assert_monochrome_output(data, 'incomplete palette')

    def test_incomplete_extended_colors_remain_a_diagnostic_failure(self):
        for command in (b'38', b'38;2;218;218', b'48;5', b'38:2::218:218'):
            with self.subTest(command=command):
                with self.assertRaises(AssertionError):
                    chromatic_sgr(b'\x1b[' + command + b'm')


if __name__ == '__main__':
    unittest.main()
