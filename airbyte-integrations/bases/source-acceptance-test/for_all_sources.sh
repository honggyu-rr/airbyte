sources=$(ls ../../connectors | grep "source")
for source in $sources; do
    docker pull airbyte/$source:latest
    python -m pytest -p source_acceptance_test.plugin -k 'TestSpec and not test_config_match_spec and not test_match_expected and not test_backward_compatibility and not test_oauth_flow_parameters and not test_defined_refs_exist_in_json_spec_file and not test_docker_env and not test_enum_usage and not test_oneof_usage and not test_required and not test_optional and not test_has_secret and not test_secret_never_in_the_output and not test_additional_properties_is_true' --acceptance-test-config=../../connectors/$source > log-$source
done